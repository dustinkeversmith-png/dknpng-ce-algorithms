package problem.space

import scala.collection.immutable.BitSet

/** Reusable immutable implementation used by the concrete domain constructors below. */
final case class ConfiguredSpace[S](
  description: String,
  shape: String,
  invariants: List[Invariant[S]],
  generator: () => S,
  enumeration: LazyList[S] = LazyList.empty,
  neighborhood: S => LazyList[S] = (_: S) => LazyList.empty,
  metric: (S, S) => Double = (_: S, _: S) => 0.0,
  projection: S => S = identity[S]
) extends Space[S]:
  def generate(): S = generator()
  override def enumerate = enumeration
  override def neighbors(s: S) = neighborhood(s).filter(contains)
  override def distance(a: S, b: S) = metric(a, b)
  override def project(s: S) = projection(s)

private object Domain:
  def invariant[S](name: String, message: S => String)(test: S => Boolean): Invariant[S] =
    Invariant(name, Predicate(name, test), message)
  def finite(x: Double) = !x.isNaN && !x.isInfinity
  def clamp(x: Double, min: Double, max: Double) = math.max(min, math.min(max, x))
  def euclidean(a: Iterable[Double], b: Iterable[Double]) =
    math.sqrt(a.iterator.zip(b.iterator).map((x, y) => (x - y) * (x - y)).sum)
  def matrixShape(a: Matrix, rows: Int, cols: Int) = a.length == rows && a.forall(_.length == cols)
  def rank(input: Matrix, epsilon: Double = 1e-10): Int =
    if input.isEmpty then 0
    else
      val a = input.map(_.toArray).toArray
      var row = 0; var col = 0
      while row < a.length && col < a(0).length do
        val pivot = (row until a.length).maxBy(i => math.abs(a(i)(col)))
        if math.abs(a(pivot)(col)) <= epsilon then col += 1
        else
          val tmp = a(row); a(row) = a(pivot); a(pivot) = tmp
          val scale = a(row)(col)
          for j <- col until a(0).length do a(row)(j) /= scale
          for i <- a.indices if i != row do
            val factor = a(i)(col)
            for j <- col until a(0).length do a(i)(j) -= factor * a(row)(j)
          row += 1; col += 1
      row

type IntegerSpace = ConfiguredSpace[Int]
object IntegerSpace:
  def apply(min: Int = Int.MinValue, max: Int = Int.MaxValue): IntegerSpace =
    require(min <= max, "min must not exceed max")
    val inv = Domain.invariant[Int]("integer bounds", x => s"$x is outside [$min, $max]")(x => x >= min && x <= max)
    val all = if min != Int.MinValue && max != Int.MaxValue then LazyList.iterate(min.toLong)(_ + 1).take((max.toLong - min + 1).toInt).map(_.toInt) else LazyList.empty
    ConfiguredSpace(s"Integers from $min through $max", s"Integer[$min,$max]", List(inv), () => if min <= 0 && max >= 0 then 0 else min, all,
      x => LazyList(x.toLong - 1, x.toLong + 1).filter(y => y >= min && y <= max).map(_.toInt),
      (a, b) => math.abs(a.toLong - b.toLong).toDouble, x => math.max(min, math.min(max, x)))

type NaturalNumberSpace = ConfiguredSpace[Int]
object NaturalNumberSpace:
  def apply(max: Int = Int.MaxValue, includeZero: Boolean = true): NaturalNumberSpace = IntegerSpace(if includeZero then 0 else 1, max).copy(description = "Natural numbers")

type RealSpace = ConfiguredSpace[Double]
object RealSpace:
  def apply(sample: () => Double = () => 0.0): RealSpace =
    ConfiguredSpace("Finite real numbers represented by Double", "Real", List(Domain.invariant[Double]("finite", x => s"$x is not finite")(Domain.finite)),
      () => project(sample()), neighborhood = x => LazyList(x - 1, x + 1), metric = (a, b) => math.abs(a - b), projection = project)
  private def project(x: Double) = if x.isNaN then 0.0 else if x == Double.PositiveInfinity then Double.MaxValue else if x == Double.NegativeInfinity then -Double.MaxValue else x

type IntervalSpace = ConfiguredSpace[Double]
object IntervalSpace:
  def apply(min: Double, max: Double, sample: () => Double = () => .5): IntervalSpace =
    require(Domain.finite(min) && Domain.finite(max) && min <= max, "interval endpoints must be finite and ordered")
    val project = (x: Double) => if x.isNaN then min else Domain.clamp(x, min, max)
    ConfiguredSpace(s"Floating-point interval [$min,$max]", s"Interval[$min,$max]", List(Domain.invariant[Double]("interval bounds", x => s"$x is outside [$min,$max]")(x => Domain.finite(x) && x >= min && x <= max)),
      () => project(min + (max - min) * Domain.clamp(sample(), 0, 1)), neighborhood = x => LazyList(project(x - math.max(math.ulp(project(x)), (max-min)/100)), project(x + math.max(math.ulp(project(x)), (max-min)/100))).distinct,
      metric = (a, b) => math.abs(a-b), projection = project)

type BitsetSpace = ConfiguredSpace[BitSet]
object BitsetSpace:
  def apply(size: Int): BitsetSpace =
    require(size >= 0)
    ConfiguredSpace(s"Bitmasks of width $size", s"{0,1}^$size", List(Domain.invariant[BitSet]("bit indices", _ => "bit index outside width")(_.forall(i => i >= 0 && i < size))), () => BitSet.empty,
      if size <= 20 then LazyList.range(0, 1 << size).map(m => BitSet.fromSpecific((0 until size).filter(i => (m & 1 << i) != 0))) else LazyList.empty,
      b => LazyList.from(0 until size).map(i => if b(i) then b - i else b + i), (a,b) => (a diff b).size + (b diff a).size, _.filter(i => i >= 0 && i < size))

type CategoricalSpace[A] = ConfiguredSpace[A]
object CategoricalSpace:
  def apply[A](input: Vector[A]): CategoricalSpace[A] =
    require(input.nonEmpty); val values = input.distinct
    ConfiguredSpace(s"Categorical enumeration of ${values.size} values", s"Enum[${values.mkString(",")}]", List(Domain.invariant[A]("category membership", x => s"$x is not allowed")(values.contains)),
      () => values.head, LazyList.from(values), x => LazyList.from(values.filterNot(_ == x)), (a,b) => if a == b then 0 else 1, x => if values.contains(x) then x else values.head)

type FixedLengthVectorSpace[A] = ConfiguredSpace[Vector[A]]
object FixedLengthVectorSpace:
  def apply[A](elements: Space[A], length: Int): FixedLengthVectorSpace[A] =
    require(length >= 0)
    def enumeration(n: Int): LazyList[Vector[A]] = if n == 0 then LazyList(Vector.empty) else for xs <- enumeration(n-1); x <- elements.enumerate yield xs :+ x
    val project = (xs: Vector[A]) => xs.take(length).map(elements.project).padTo(length, elements.generate())
    ConfiguredSpace(s"Fixed-length vectors over ${elements.description}", s"Vector[$length] of (${elements.shape})", List(
      Domain.invariant[Vector[A]]("fixed length", x => s"expected $length elements, got ${x.length}")(_.length == length), Domain.invariant[Vector[A]]("element membership", _ => "invalid element")(_.forall(elements.contains))),
      () => Vector.fill(length)(elements.generate()), enumeration(length), xs => LazyList.from(xs.indices).flatMap(i => elements.neighbors(xs(i)).map(x => xs.updated(i,x))),
      (a,b) => if a.length != b.length then Double.PositiveInfinity else math.sqrt(a.indices.map(i => math.pow(elements.distance(a(i),b(i)),2)).sum), project)

type DynamicSequenceSpace[A] = ConfiguredSpace[Vector[A]]
object DynamicSequenceSpace:
  def apply[A](elements: Space[A], minLength: Int = 0, maxLength: Int = 100): DynamicSequenceSpace[A] =
    require(minLength >= 0 && maxLength >= minLength)
    val project = (xs: Vector[A]) => xs.take(maxLength).map(elements.project).padTo(minLength,elements.generate())
    ConfiguredSpace(s"Sequences over ${elements.description}", s"Vector[$minLength..$maxLength] of (${elements.shape})", List(
      Domain.invariant[Vector[A]]("length bounds", _ => "length outside bounds")(x => x.length >= minLength && x.length <= maxLength), Domain.invariant[Vector[A]]("element membership", _ => "invalid element")(_.forall(elements.contains))),
      () => Vector.fill(minLength)(elements.generate()), neighborhood = xs => LazyList.from(xs.indices).flatMap(i => elements.neighbors(xs(i)).map(x => xs.updated(i,x))) #::: (if xs.length < maxLength then LazyList(xs :+ elements.generate()) else LazyList.empty) #::: (if xs.length > minLength then LazyList(xs.dropRight(1)) else LazyList.empty),
      metric = (a,b) => a.zip(b).map(elements.distance).sum + math.abs(a.length-b.length), projection = project)

type DistinctSequenceSpace[A] = ConfiguredSpace[Vector[A]]
object DistinctSequenceSpace:
  def apply[A](elements: Space[A], minLength: Int = 0, maxLength: Int = 100): DistinctSequenceSpace[A] =
    val base = DynamicSequenceSpace(elements,minLength,maxLength)
    base.copy(description = "Distinct element sequences", shape = s"Distinct[${base.shape}]", invariants = base.invariants :+ Domain.invariant[Vector[A]]("distinct elements", _ => "elements repeat")(x => x.distinct.length == x.length),
      generator = () => base.generate().distinct, enumeration = base.enumerate.filter(x => x.distinct.length == x.length), neighborhood = x => base.neighbors(x).filter(y => y.distinct.length == y.length), projection = x => base.project(x).distinct)

type PermutationSpace[A] = ConfiguredSpace[Vector[A]]
object PermutationSpace:
  def apply[A](input: Vector[A]): PermutationSpace[A] =
    require(input.distinct.length == input.length)
    ConfiguredSpace(s"Permutations of ${input.size} elements", s"S_${input.size}", List(Domain.invariant[Vector[A]]("permutation", _ => "not a permutation")(x => x.length == input.length && x.toSet == input.toSet)), () => input,
      LazyList.from(input.permutations), x => LazyList.from(0 until math.max(0,x.length-1)).map(i => x.updated(i,x(i+1)).updated(i+1,x(i))),
      (a,b) => a.zip(b).count(_ != _), x => x.filter(input.contains).distinct ++ input.filterNot(x.contains))

type SortedSequenceSpace[A] = ConfiguredSpace[Vector[A]]
object SortedSequenceSpace:
  def apply[A: Ordering](elements: Space[A], minLength: Int = 0, maxLength: Int = 100): SortedSequenceSpace[A] =
    val base=DynamicSequenceSpace(elements,minLength,maxLength); val ord=summon[Ordering[A]]
    base.copy(description="Ordered sequences",shape=s"Sorted[${base.shape}]",invariants=base.invariants :+ Domain.invariant[Vector[A]]("sorted order",_=>"not sorted")(x=>x.zip(x.drop(1)).forall(ord.lteq)),generator=()=>base.generate().sorted,neighborhood=x=>base.neighbors(x).map(_.sorted).distinct,projection=x=>base.project(x).sorted)

type MultisetSpace[A] = ConfiguredSpace[Map[A,Int]]
object MultisetSpace:
  def apply[A](elements: Space[A], maxSize: Int = 100): MultisetSpace[A] = ConfiguredSpace("Multisets / bags",s"Bag[0..$maxSize]",List(Domain.invariant[Map[A,Int]]("multiplicities",_=>"invalid key, count, or size")(m=>m.forall((a,n)=>elements.contains(a)&&n>0)&&m.values.sum<=maxSize)),()=>Map.empty,
    metric=(a,b)=>(a.keySet++b.keySet).map(k=>math.abs(a.getOrElse(k,0)-b.getOrElse(k,0))).sum,
    projection=m=>m.filter((a,n)=>elements.contains(a)&&n>0).toVector.sortBy(_._2).foldLeft(Map.empty[A,Int])((acc,e)=>if acc.values.sum>=maxSize then acc else acc.updated(e._1,math.min(e._2,maxSize-acc.values.sum))))

type Matrix = Vector[Vector[Double]]
type DenseMatrixSpace = ConfiguredSpace[Matrix]
object DenseMatrixSpace:
  def apply(rows:Int,columns:Int,elements:Space[Double]=RealSpace()):DenseMatrixSpace =
    require(rows>=0&&columns>=0); val project=(a:Matrix)=>Vector.tabulate(rows,columns)((i,j)=>elements.project(a.lift(i).flatMap(_.lift(j)).getOrElse(elements.generate())))
    ConfiguredSpace(s"Dense $rows by $columns matrices",s"Matrix[$rows,$columns]",List(Domain.invariant[Matrix]("matrix shape",_=>"wrong matrix shape")(Domain.matrixShape(_,rows,columns)),Domain.invariant[Matrix]("matrix elements",_=>"invalid matrix entry")(_.forall(_.forall(elements.contains)))),()=>Vector.fill(rows,columns)(elements.generate()),
      neighborhood=a=>LazyList.from(a.indices).flatMap(i=>LazyList.from(a(i).indices).flatMap(j=>elements.neighbors(a(i)(j)).map(x=>a.updated(i,a(i).updated(j,x))))),metric=(a,b)=>if !Domain.matrixShape(a,rows,columns) || !Domain.matrixShape(b,rows,columns) then Double.PositiveInfinity else Domain.euclidean(a.flatten,b.flatten),projection=project)

type SquareMatrixSpace = ConfiguredSpace[Matrix]
object SquareMatrixSpace:
  def apply(size:Int,elements:Space[Double]=RealSpace())=DenseMatrixSpace(size,size,elements).copy(description=s"Square $size by $size matrices",shape=s"SquareMatrix[$size]")

type InvertibleMatrixSpace = ConfiguredSpace[Matrix]
object InvertibleMatrixSpace:
  def apply(size:Int,epsilon:Double=1e-10):InvertibleMatrixSpace =
    val base=SquareMatrixSpace(size); val id=Vector.tabulate(size,size)((i,j)=>if i==j then 1.0 else 0.0)
    base.copy(description="Invertible / full-rank matrices",shape=s"GL_$size",invariants=base.invariants :+ Domain.invariant[Matrix]("full rank",_=>"matrix is singular")(Domain.rank(_,epsilon)==size),generator=()=>id,neighborhood=a=>base.neighbors(a).filter(Domain.rank(_,epsilon)==size),projection=a=>if Domain.rank(a,epsilon)==size then a else id)

type SymmetricMatrixSpace = ConfiguredSpace[Matrix]
object SymmetricMatrixSpace:
  def apply(size:Int,elements:Space[Double]=RealSpace(),epsilon:Double=1e-10):SymmetricMatrixSpace =
    val base=SquareMatrixSpace(size,elements); val project=(a:Matrix)=>{val p=base.project(a);Vector.tabulate(size,size)((i,j)=>elements.project((p(i)(j)+p(j)(i))/2))}
    base.copy(description="Symmetric matrices",shape=s"SymmetricMatrix[$size]",invariants=base.invariants :+ Domain.invariant[Matrix]("symmetry",_=>"matrix is not symmetric")(a=>a.indices.forall(i=>a.indices.forall(j=>math.abs(a(i)(j)-a(j)(i))<=epsilon))),neighborhood=a=>base.neighbors(a).map(project).distinct,projection=project)

final case class SparseMatrix(rows:Int,columns:Int,entries:Map[(Int,Int),Double]):
  def apply(i:Int,j:Int)=entries.getOrElse((i,j),0.0)
  def dense:Matrix=Vector.tabulate(rows,columns)(apply)
type SparseMatrixSpace = ConfiguredSpace[SparseMatrix]
object SparseMatrixSpace:
  def apply(rows:Int,columns:Int,maxNonZero:Int,elements:Space[Double]=RealSpace()):SparseMatrixSpace = ConfiguredSpace("Sparse matrices",s"SparseMatrix[$rows,$columns;$maxNonZero]",List(Domain.invariant[SparseMatrix]("sparse structure",_=>"invalid sparse matrix")(a=>a.rows==rows&&a.columns==columns&&a.entries.size<=maxNonZero&&a.entries.forall{case((i,j),x)=>i>=0&&i<rows&&j>=0&&j<columns&&x!=0&&elements.contains(x)})),()=>SparseMatrix(rows,columns,Map.empty),metric=(a,b)=>Domain.euclidean(a.dense.flatten,b.dense.flatten),projection=a=>SparseMatrix(rows,columns,a.entries.filter{case((i,j),x)=>i>=0&&i<rows&&j>=0&&j<columns&&x!=0}.take(maxNonZero).map((k,x)=>k->elements.project(x))))

type RowStochasticMatrixSpace=ConfiguredSpace[Matrix]
object RowStochasticMatrixSpace:
  def apply(size:Int,epsilon:Double=1e-9):RowStochasticMatrixSpace =
    val base=SquareMatrixSpace(size,IntervalSpace(0,1)); val project=(a:Matrix)=>base.project(a).map(r=>{val s=r.map(math.max(0,_)).sum;if s==0&&size>0 then Vector.fill(size)(1.0/size) else r.map(math.max(0,_)/s)})
    base.copy(description="Row-stochastic / Markov matrices",shape=s"MarkovMatrix[$size]",invariants=base.invariants :+ Domain.invariant[Matrix]("row stochastic",_=>"row does not sum to one")(_.forall(r=>math.abs(r.sum-1)<=epsilon)),generator=()=>if size==0 then Vector.empty else Vector.fill(size,size)(1.0/size),projection=project)

type OrthogonalMatrixSpace=ConfiguredSpace[Matrix]
object OrthogonalMatrixSpace:
  def apply(size:Int,epsilon:Double=1e-9):OrthogonalMatrixSpace =
    val base=SquareMatrixSpace(size);val id=Vector.tabulate(size,size)((i,j)=>if i==j then 1.0 else 0.0);val ok=(a:Matrix)=>Domain.matrixShape(a,size,size)&&a.indices.forall(i=>a.indices.forall(j=>math.abs(a.indices.map(k=>a(k)(i)*a(k)(j)).sum-(if i==j then 1 else 0))<=epsilon))
    base.copy(description="Orthogonal / real unitary matrices",shape=s"O($size)",invariants=base.invariants :+ Domain.invariant[Matrix]("orthogonality",_=>"columns are not orthonormal")(ok),generator=()=>id,projection=a=>if ok(a) then a else id)
type UnitaryMatrixSpace=OrthogonalMatrixSpace
val UnitaryMatrixSpace=OrthogonalMatrixSpace

final case class Tensor(dimensions:Vector[Int],values:Vector[Double])
type TensorSpace=ConfiguredSpace[Tensor]
object TensorSpace:
  def apply(dimensions:Vector[Int],elements:Space[Double]=RealSpace()):TensorSpace =
    require(dimensions.forall(_>=0));val n=dimensions.product;val project=(t:Tensor)=>Tensor(dimensions,t.values.take(n).map(elements.project).padTo(n,elements.generate()))
    ConfiguredSpace("Multi-dimensional tensors",s"Tensor[${dimensions.mkString("×")} ]",List(Domain.invariant[Tensor]("tensor shape",_=>"wrong tensor shape")(t=>t.dimensions==dimensions&&t.values.length==n),Domain.invariant[Tensor]("tensor elements",_=>"invalid tensor entry")(_.values.forall(elements.contains))),()=>Tensor(dimensions,Vector.fill(n)(elements.generate())),neighborhood=t=>LazyList.from(t.values.indices).flatMap(i=>elements.neighbors(t.values(i)).map(x=>t.copy(values=t.values.updated(i,x)))),metric=(a,b)=>if a.dimensions!=b.dimensions then Double.PositiveInfinity else Domain.euclidean(a.values,b.values),projection=project)

final case class Graph[V](adjacency:Map[V,Set[V]]):
  def vertices:Set[V]=adjacency.keySet++adjacency.values.flatten
  def edges:Set[(V,V)]=adjacency.flatMap((u,vs)=>vs.map(u->_)).toSet
type GraphSpace[V]=ConfiguredSpace[Graph[V]]
object GraphSpace:
  def apply[V](vertices:Space[V],directed:Boolean=true,allowSelfLoops:Boolean=true,maxVertices:Int=100):GraphSpace[V] =
    val valid=(g:Graph[V])=>g.vertices.size<=maxVertices&&g.vertices.forall(vertices.contains)&&(allowSelfLoops||g.edges.forall(_!=_))&&(directed||g.edges.forall((u,v)=>g.adjacency.getOrElse(v,Set.empty).contains(u)))
    val project=(g:Graph[V])=>{val vs=g.vertices.filter(vertices.contains).take(maxVertices);val es=g.edges.filter((u,v)=>vs(u)&&vs(v)&&(allowSelfLoops||u!=v));val both=if directed then es else es++es.map(_.swap);Graph(vs.map(v=>v->both.collect{case(`v`,w)=>w}).toMap)}
    ConfiguredSpace(if directed then "Directed adjacency-list graphs" else "Undirected adjacency-list graphs",s"Graph[0..$maxVertices]",List(Domain.invariant[Graph[V]]("graph structure",_=>"invalid graph")(valid)),()=>Graph(Map.empty),metric=(a,b)=>(a.vertices diff b.vertices).size+(b.vertices diff a.vertices).size+(a.edges diff b.edges).size+(b.edges diff a.edges).size,projection=project)

type DirectedAcyclicGraphSpace[V]=ConfiguredSpace[Graph[V]]
object DirectedAcyclicGraphSpace:
  def apply[V](vertices:Space[V],maxVertices:Int=100):DirectedAcyclicGraphSpace[V] =
    val base=GraphSpace(vertices,true,false,maxVertices)
    def acyclic(g:Graph[V]):Boolean =
      def visit(v:V,active:Set[V],done:Set[V]):Option[Set[V]]=if active(v) then None else if done(v) then Some(done) else g.adjacency.getOrElse(v,Set.empty).foldLeft(Option(done))((d,w)=>d.flatMap(visit(w,active+v,_))).map(_+v)
      g.vertices.foldLeft(Option(Set.empty[V]))((d,v)=>d.flatMap(visit(v,Set.empty,_))).nonEmpty
    base.copy(description="Directed acyclic graphs",shape=s"DAG[0..$maxVertices]",invariants=base.invariants :+ Domain.invariant[Graph[V]]("acyclic",_=>"graph contains a cycle")(acyclic),projection=g=>if base.contains(g)&&acyclic(g) then g else Graph(Map.empty))

final case class Tree[A](value:A,children:Vector[Tree[A]]=Vector.empty):
  def size:Int=1+children.map(_.size).sum
  def depth:Int=1+children.map(_.depth).maxOption.getOrElse(0)
type TreeSpace[A]=ConfiguredSpace[Tree[A]]
object TreeSpace:
  def apply[A](values:Space[A],maxDepth:Int=32,maxNodes:Int=1000):TreeSpace[A] =
    def valid(t:Tree[A]):Boolean=t.size<=maxNodes&&t.depth<=maxDepth&&values.contains(t.value)&&t.children.forall(valid)
    val root=()=>Tree[A](values.generate(),Vector.empty);ConfiguredSpace("Tree hierarchies / ASTs",s"Tree[depth≤$maxDepth,nodes≤$maxNodes]",List(Domain.invariant[Tree[A]]("tree structure",_=>"invalid or oversized tree")(valid)),root,metric=(a,b)=>values.distance(a.value,b.value)+math.abs(a.size-b.size),projection=t=>if valid(t) then t else root())

final case class WeightedEdge[V,W](from:V,to:V,weight:W)
type EdgeTupleSpace[V,W]=ConfiguredSpace[WeightedEdge[V,W]]
object EdgeTupleSpace:
  def apply[V,W](vertices:Space[V],weights:Space[W],allowSelfLoops:Boolean=true):EdgeTupleSpace[V,W]=ConfiguredSpace("Node / edge parameter tuples",s"(${vertices.shape},${vertices.shape},${weights.shape})",List(Domain.invariant[WeightedEdge[V,W]]("edge tuple",_=>"invalid edge tuple")(e=>vertices.contains(e.from)&&vertices.contains(e.to)&&weights.contains(e.weight)&&(allowSelfLoops||e.from!=e.to))),()=>WeightedEdge(vertices.generate(),vertices.generate(),weights.generate()),metric=(a,b)=>vertices.distance(a.from,b.from)+vertices.distance(a.to,b.to)+weights.distance(a.weight,b.weight),projection=e=>WeightedEdge(vertices.project(e.from),vertices.project(e.to),weights.project(e.weight)))

final case class Bipartition[A](left:Set[A],right:Set[A])
type BipartitePartitionSpace[A]=ConfiguredSpace[Bipartition[A]]
object BipartitePartitionSpace:
  def apply[A](elements:Space[A],maxElements:Int=100):BipartitePartitionSpace[A]=ConfiguredSpace("Bipartite partitions",s"Bipartition[0..$maxElements]",List(Domain.invariant[Bipartition[A]]("bipartition",_=>"partitions overlap or contain invalid elements")(p=>(p.left intersect p.right).isEmpty&&p.left.size+p.right.size<=maxElements&&(p.left++p.right).forall(elements.contains))),()=>Bipartition(Set.empty,Set.empty),metric=(a,b)=>(a.left diff b.left).size+(b.left diff a.left).size+(a.right diff b.right).size+(b.right diff a.right).size,projection=p=>{val l=p.left.filter(elements.contains).take(maxElements);Bipartition(l,p.right.filter(elements.contains).diff(l).take(maxElements-l.size))})

type AlphabetStringSpace=ConfiguredSpace[String]
object AlphabetStringSpace:
  def apply(input:Vector[Char],minLength:Int=0,maxLength:Int=100):AlphabetStringSpace =
    require(input.nonEmpty&&minLength>=0&&maxLength>=minLength);val chars=input.distinct
    def words(n:Int):LazyList[String]=if n==0 then LazyList("") else for p<-words(n-1);c<-LazyList.from(chars) yield p+c
    val project=(s:String)=>s.filter(chars.contains).take(maxLength).padTo(minLength,chars.head)
    ConfiguredSpace(s"Strings over alphabet {${chars.mkString}}",s"Σ^[$minLength..$maxLength]",List(Domain.invariant[String]("string length",_=>"length outside bounds")(s=>s.length>=minLength&&s.length<=maxLength),Domain.invariant[String]("alphabet",_=>"character outside alphabet")(_.forall(chars.contains))),()=>chars.head.toString*minLength,LazyList.range(minLength,maxLength+1).flatMap(words),s=>LazyList.from(s.indices).flatMap(i=>LazyList.from(chars.filterNot(_==s(i))).map(c=>s.updated(i,c))) #::: (if s.length<maxLength then LazyList(s+chars.head) else LazyList.empty) #::: (if s.length>minLength then LazyList(s.dropRight(1)) else LazyList.empty),
      (a,b)=>a.zip(b).count(_!=_)+math.abs(a.length-b.length),project)

type TokenSequenceSpace[T]=DynamicSequenceSpace[T]
object TokenSequenceSpace:
  def apply[T](tokens:Space[T],minLength:Int=0,maxLength:Int=100)=DynamicSequenceSpace(tokens,minLength,maxLength).copy(description="Token sequences")

type DictionarySpace[K,V]=ConfiguredSpace[Map[K,V]]
object DictionarySpace:
  def apply[K,V](keys:Space[K],values:Space[V],maxEntries:Int=100):DictionarySpace[K,V]=ConfiguredSpace("Key-value dictionaries / environment maps",s"Map[${keys.shape},${values.shape};0..$maxEntries]",List(Domain.invariant[Map[K,V]]("dictionary",_=>"invalid dictionary")(m=>m.size<=maxEntries&&m.forall((k,v)=>keys.contains(k)&&values.contains(v)))),()=>Map.empty,metric=(a,b)=>(a.keySet++b.keySet).map(k=>(a.get(k),b.get(k))match{case(Some(x),Some(y))=>values.distance(x,y);case _=>1.0}).sum,projection=m=>m.filter((k,_)=>keys.contains(k)).take(maxEntries).map((k,v)=>k->values.project(v)))

final case class GrammarProduction[N,T](left:N,right:Vector[Either[N,T]])
type GrammarProductionSpace[N,T]=ConfiguredSpace[GrammarProduction[N,T]]
object GrammarProductionSpace:
  def apply[N,T](nonTerminals:Space[N],terminals:Space[T],maxRightLength:Int=100):GrammarProductionSpace[N,T]=ConfiguredSpace("Formal grammar productions",s"${nonTerminals.shape}→(${nonTerminals.shape}|${terminals.shape})*",List(Domain.invariant[GrammarProduction[N,T]]("grammar production",_=>"invalid production")(p=>nonTerminals.contains(p.left)&&p.right.length<=maxRightLength&&p.right.forall(_.fold(nonTerminals.contains,terminals.contains)))),()=>GrammarProduction(nonTerminals.generate(),Vector.empty),metric=(a,b)=>nonTerminals.distance(a.left,b.left)+math.abs(a.right.length-b.right.length),projection=p=>GrammarProduction(nonTerminals.project(p.left),p.right.take(maxRightLength).filter(_.fold(nonTerminals.contains,terminals.contains))))

type PrefixFreeSetSpace=ConfiguredSpace[Set[String]]
object PrefixFreeSetSpace:
  def apply(alphabet:Vector[Char],maxWords:Int=100,maxWordLength:Int=100):PrefixFreeSetSpace =
    val strings=AlphabetStringSpace(alphabet,0,maxWordLength);def ok(w:Set[String]) = !w.exists(a=>w.exists(b=>a!=b&&b.startsWith(a)));val project=(w:Set[String])=>w.filter(strings.contains).toVector.sortBy(_.length).foldLeft(Set.empty[String])((a,s)=>if a.exists(s.startsWith) then a else a+s).take(maxWords)
    ConfiguredSpace("Prefix-free / trie path sets",s"PrefixFreeSet[0..$maxWords]",List(Domain.invariant[Set[String]]("prefix-free",_=>"words are invalid or prefix another word")(w=>w.size<=maxWords&&w.forall(strings.contains)&&ok(w))),()=>Set.empty,metric=(a,b)=>(a diff b).size+(b diff a).size,projection=project)

final case class Point(coordinates:Vector[Double])
type CartesianSpace=ConfiguredSpace[Point]
object CartesianSpace:
  def apply(axes:Vector[Space[Double]]):CartesianSpace =
    val project=(p:Point)=>Point(axes.indices.map(i=>axes(i).project(p.coordinates.lift(i).getOrElse(axes(i).generate()))).toVector)
    ConfiguredSpace("Cartesian coordinate tuples",s"(${axes.map(_.shape).mkString("×")})",List(Domain.invariant[Point]("coordinate dimensions",_=>"wrong dimensions")(_.coordinates.length==axes.length),Domain.invariant[Point]("coordinate bounds",_=>"invalid coordinate")(p=>p.coordinates.indices.forall(i=>axes(i).contains(p.coordinates(i))))),()=>Point(axes.map(_.generate())),neighborhood=p=>LazyList.from(p.coordinates.indices).flatMap(i=>axes(i).neighbors(p.coordinates(i)).map(x=>Point(p.coordinates.updated(i,x)))),metric=(a,b)=>Domain.euclidean(a.coordinates,b.coordinates),projection=project)

final case class AxisAlignedBoundingBox(min:Point,max:Point)
type BoundingBoxSpace=ConfiguredSpace[AxisAlignedBoundingBox]
object BoundingBoxSpace:
  def apply(dimensions:Int,coordinates:Space[Double]=RealSpace()):BoundingBoxSpace =
    val points=CartesianSpace(Vector.fill(dimensions)(coordinates));val project=(b:AxisAlignedBoundingBox)=>{val p=points.project(b.min);val q=points.project(b.max);AxisAlignedBoundingBox(Point(p.coordinates.zip(q.coordinates).map((x,y)=>math.min(x,y))),Point(p.coordinates.zip(q.coordinates).map((x,y)=>math.max(x,y))))}
    ConfiguredSpace("Axis-aligned bounding boxes",s"AABB[$dimensions]",List(Domain.invariant[AxisAlignedBoundingBox]("bounding box",_=>"invalid corners")(b=>points.contains(b.min)&&points.contains(b.max)&&b.min.coordinates.zip(b.max.coordinates).forall(_<=_))),()=>{val p=points.generate();AxisAlignedBoundingBox(p,p)},metric=(a,b)=>points.distance(a.min,b.min)+points.distance(a.max,b.max),projection=project)

final case class Polygon(vertices:Vector[Point])
type ConvexPolygonSpace=ConfiguredSpace[Polygon]
object ConvexPolygonSpace:
  def apply(coordinates:Space[Double]=RealSpace(),maxVertices:Int=100,epsilon:Double=1e-10):ConvexPolygonSpace =
    def convex(p:Polygon)=if p.vertices.length<3||p.vertices.exists(_.coordinates.length!=2) then false else {val z=p.vertices.indices.map{i=>val a=p.vertices(i).coordinates;val b=p.vertices((i+1)%p.vertices.length).coordinates;val c=p.vertices((i+2)%p.vertices.length).coordinates;(b(0)-a(0))*(c(1)-b(1))-(b(1)-a(1))*(c(0)-b(0))}.filter(math.abs(_)>epsilon);z.nonEmpty&&(z.forall(_>0)||z.forall(_<0))}
    val triangle=()=>Polygon(Vector(Point(Vector(0,0)),Point(Vector(1,0)),Point(Vector(0,1))).map(p=>Point(p.coordinates.map(coordinates.project))))
    ConfiguredSpace("Convex polygons / meshes",s"ConvexPolygon[3..$maxVertices]",List(Domain.invariant[Polygon]("convex polygon",_=>"polygon is not valid and convex")(p=>p.vertices.length<=maxVertices&&p.vertices.forall(_.coordinates.forall(coordinates.contains))&&convex(p))),triangle,metric=(a,b)=>if a.vertices.length!=b.vertices.length then Double.PositiveInfinity else a.vertices.zip(b.vertices).map((x,y)=>Domain.euclidean(x.coordinates,y.coordinates)).sum,projection=p=>if convex(p) then p else triangle())

type PointCloudSpace=ConfiguredSpace[Vector[Point]]
object PointCloudSpace:
  def apply(dimensions:Int,coordinates:Space[Double]=RealSpace(),minPoints:Int=0,maxPoints:Int=1000):PointCloudSpace =
    val point=CartesianSpace(Vector.fill(dimensions)(coordinates));ConfiguredSpace("Spatial point clouds",s"PointCloud[$minPoints..$maxPoints]",List(Domain.invariant[Vector[Point]]("point cloud",_=>"invalid point cloud")(p=>p.length>=minPoints&&p.length<=maxPoints&&p.forall(point.contains))),()=>Vector.fill(minPoints)(point.generate()),metric=(a,b)=>a.zip(b).map(point.distance).sum+math.abs(a.length-b.length),projection=p=>p.take(maxPoints).map(point.project).padTo(minPoints,point.generate()))

type PositionalArgumentsSpace=ConfiguredSpace[Vector[Any]]
object PositionalArgumentsSpace:
  def apply(arguments:Vector[Space[?]]):PositionalArgumentsSpace =
    val spaces=arguments.map(_.asInstanceOf[Space[Any]])
    ConfiguredSpace("Positional argument tuples",s"(${spaces.map(_.shape).mkString("×")})",List(Domain.invariant[Vector[Any]]("argument tuple",_=>"invalid argument tuple")(x=>x.length==spaces.length&&x.indices.forall(i=>spaces(i).contains(x(i))))),()=>spaces.map(_.generate()),metric=(a,b)=>if a.length!=spaces.length||b.length!=spaces.length then Double.PositiveInfinity else spaces.indices.map(i=>spaces(i).distance(a(i),b(i))).sum,projection=x=>spaces.indices.map(i=>x.lift(i).map(spaces(i).project).getOrElse(spaces(i).generate())).toVector)

type KeywordParameterSpace=ConfiguredSpace[Map[String,Any]]
object KeywordParameterSpace:
  def apply(parameters:Map[String,Space[?]],required:Set[String]=Set.empty,allowUnknown:Boolean=false):KeywordParameterSpace =
    val spaces=parameters.view.mapValues(_.asInstanceOf[Space[Any]]).toMap
    require(required.subsetOf(spaces.keySet));ConfiguredSpace("Keyword / option parameter maps",s"Options{${spaces.keys.toVector.sorted.mkString(",")}}",List(Domain.invariant[Map[String,Any]]("parameter map",_=>"invalid parameter map")(m=>required.subsetOf(m.keySet)&&m.forall((k,v)=>spaces.get(k).exists(_.contains(v)) || (allowUnknown && !spaces.contains(k))))),()=>required.map(k=>k->spaces(k).generate()).toMap,metric=(a,b)=>(a.keySet++b.keySet).map(k=>(a.get(k),b.get(k),spaces.get(k))match{case(Some(x),Some(y),Some(s))=>s.distance(x,y);case(Some(x),Some(y),None)=>if x==y then 0.0 else 1.0;case _=>1.0}).sum,projection=m=>spaces.flatMap((k,s)=>m.get(k).map(v=>k->s.project(v)).orElse(if required(k) then Some(k->s.generate()) else None)))

type HyperparameterGridSpace[A]=ConfiguredSpace[Map[String,A]]
object HyperparameterGridSpace:
  def apply[A](axes:Vector[(String,Vector[A])]):HyperparameterGridSpace[A] =
    require(axes.map(_._1).distinct.length==axes.length&&axes.forall(_._2.nonEmpty));val map=axes.toMap;val project=(p:Map[String,A])=>axes.map((k,v)=>k->p.get(k).filter(v.contains).getOrElse(v.head)).toMap
    ConfiguredSpace("Bounded hyperparameter grids",axes.map((k,v)=>s"$k:${v.size}").mkString("Grid[","×","]"),List(Domain.invariant[Map[String,A]]("grid point",_=>"invalid grid point")(p=>p.keySet==map.keySet&&p.forall((k,v)=>map(k).contains(v)))),()=>axes.map((k,v)=>k->v.head).toMap,axes.foldLeft(LazyList(Map.empty[String,A]))((ps,a)=>for p<-ps;v<-LazyList.from(a._2)yield p.updated(a._1,v)),p=>LazyList.from(axes).flatMap((k,v)=>LazyList.from(v.filterNot(_==p.getOrElse(k,v.head))).map(x=>p.updated(k,x))),(a,b)=>axes.count((k,_)=>a.get(k)!=b.get(k)),project)

type FunctionSolutionSpace=ConfiguredSpace[Vector[Double]]
object FunctionSolutionSpace:
  def apply(dimensions:Vector[IntervalSpace],feasible:Vector[Double]=>Boolean=_=>true):FunctionSolutionSpace =
    val base=CartesianSpace(dimensions);val gen=()=>{val x=dimensions.map(_.generate());if feasible(x) then x else throw IllegalStateException("generated point is infeasible")};val project=(x:Vector[Double])=>{val p=base.project(Point(x)).coordinates;if feasible(p) then p else gen()}
    ConfiguredSpace("Function solution / feasible parameter regions",s"Ω⊆R^${dimensions.length}",List(Domain.invariant[Vector[Double]]("parameter bounds",_=>"invalid parameter vector")(x=>base.contains(Point(x))),Domain.invariant[Vector[Double]]("feasibility",_=>"infeasible parameter vector")(feasible)),gen,neighborhood=x=>base.neighbors(Point(x)).map(_.coordinates).filter(feasible),metric=Domain.euclidean,projection=project)

type ActionAlphabetSpace[A]=CategoricalSpace[A]
object ActionAlphabetSpace:
  def apply[A](actions:Vector[A])=CategoricalSpace(actions).copy(description="Action / transition alphabets")

private def stateSetSpace[S](states:Space[S],maxSize:Int,label:String):ConfiguredSpace[Set[S]]=ConfiguredSpace(s"$label subsets",s"$label[0..$maxSize]⊆(${states.shape})",List(Domain.invariant[Set[S]](s"$label set",_=>s"invalid $label set")(x=>x.size<=maxSize&&x.forall(states.contains))),()=>Set.empty,metric=(a,b)=>(a diff b).size+(b diff a).size,projection=_.filter(states.contains).take(maxSize))
type FrontierSpace[S]=ConfiguredSpace[Set[S]]
object FrontierSpace:
  def apply[S](states:Space[S],maxSize:Int=10000)=stateSetSpace(states,maxSize,"Open/frontier")
type ExploredSpace[S]=ConfiguredSpace[Set[S]]
object ExploredSpace:
  def apply[S](states:Space[S],maxSize:Int=10000)=stateSetSpace(states,maxSize,"Closed/explored")

type IntegerSet=IntegerSpace;val IntegerSet=IntegerSpace
type NaturalNumberSet=NaturalNumberSpace;val NaturalNumberSet=NaturalNumberSpace
type RealNumberSpace=RealSpace;val RealNumberSpace=RealSpace
type FloatingPointInterval=IntervalSpace;val FloatingPointInterval=IntervalSpace
type FixedLengthArraySpace[A]=FixedLengthVectorSpace[A];val FixedLengthArraySpace=FixedLengthVectorSpace
type UniqueSequenceSpace[A]=DistinctSequenceSpace[A];val UniqueSequenceSpace=DistinctSequenceSpace
type MatrixSpace=DenseMatrixSpace;val MatrixSpace=DenseMatrixSpace
type DAGSpace[V]=DirectedAcyclicGraphSpace[V];val DAGSpace=DirectedAcyclicGraphSpace
type AABBSpace=BoundingBoxSpace;val AABBSpace=BoundingBoxSpace
type FeasibleParameterSpace=FunctionSolutionSpace;val FeasibleParameterSpace=FunctionSolutionSpace
