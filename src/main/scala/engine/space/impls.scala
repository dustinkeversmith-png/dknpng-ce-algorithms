package problem.space

import scala.collection.immutable.BitSet

private object SpaceChecks:
  def invariant[S](name: String, message: S => String)(test: S => Boolean): Invariant[S] =
    Invariant(name, Predicate(name, test), message)
  def finite(x: Double): Boolean = !x.isNaN && !x.isInfinity
  def clamp(x: Double, min: Double, max: Double): Double = math.max(min, math.min(max, x))
  def euclidean(a: Iterable[Double], b: Iterable[Double]): Double =
    math.sqrt(a.iterator.zip(b.iterator).map((x, y) => (x - y) * (x - y)).sum)
  def matrixShape(a: Matrix, rows: Int, columns: Int): Boolean =
    a.length == rows && a.forall(_.length == columns)
  def rank(input: Matrix, epsilon: Double = 1e-10): Int =
    if input.isEmpty then 0
    else
      val a = input.map(_.toArray).toArray
      var row = 0
      var column = 0
      while row < a.length && column < a(0).length do
        val pivot = (row until a.length).maxBy(i => math.abs(a(i)(column)))
        if math.abs(a(pivot)(column)) <= epsilon then column += 1
        else
          val temporary = a(row); a(row) = a(pivot); a(pivot) = temporary
          val scale = a(row)(column)
          for j <- column until a(0).length do a(row)(j) /= scale
          for i <- a.indices if i != row do
            val factor = a(i)(column)
            for j <- column until a(0).length do a(i)(j) -= factor * a(row)(j)
          row += 1
          column += 1
      row

final case class IntegerSpace(min: Int = Int.MinValue, max: Int = Int.MaxValue) extends Space[Int]:
  require(min <= max, "min must not exceed max")
  def description = s"Integers from $min through $max"
  def shape = s"Integer[$min,$max]"
  def invariants = List(SpaceChecks.invariant[Int]("integer bounds", x => s"$x is outside [$min,$max]")(x => x >= min && x <= max))
  def generate() = if min <= 0 && max >= 0 then 0 else min
  override def enumerate =
    if min == Int.MinValue || max == Int.MaxValue then LazyList.empty
    else LazyList.iterate(min.toLong)(_ + 1).take((max.toLong - min + 1).toInt).map(_.toInt)
  override def neighbors(x: Int) = LazyList(x.toLong - 1, x.toLong + 1).filter(y => y >= min && y <= max).map(_.toInt)
  override def distance(a: Int, b: Int) = math.abs(a.toLong - b.toLong).toDouble
  override def project(x: Int) = math.max(min, math.min(max, x))

final case class NaturalNumberSpace(max: Int = Int.MaxValue, includeZero: Boolean = true) extends Space[Int]:
  private val min = if includeZero then 0 else 1
  require(max >= min, s"max must be at least $min")
  def description = if includeZero then s"Natural numbers in [0,$max]" else s"Positive natural numbers in [1,$max]"
  def shape = s"Natural[$min,$max]"
  def invariants = List(SpaceChecks.invariant[Int]("natural-number bounds", x => s"$x is outside [$min,$max]")(x => x >= min && x <= max))
  def generate() = min
  override def enumerate = if max == Int.MaxValue then LazyList.empty else LazyList.range(min, max + 1)
  override def neighbors(x: Int) = LazyList(x.toLong - 1, x.toLong + 1).filter(y => y >= min && y <= max).map(_.toInt)
  override def distance(a: Int, b: Int) = math.abs(a.toLong - b.toLong).toDouble
  override def project(x: Int) = math.max(min, math.min(max, x))

final case class RealSpace(sample: () => Double = () => 0.0) extends Space[Double]:
  def description = "Finite real numbers represented by Double"
  def shape = "Real"
  def invariants = List(SpaceChecks.invariant[Double]("finite", x => s"$x is not finite")(SpaceChecks.finite))
  def generate() = project(sample())
  override def neighbors(x: Double) = LazyList(x - 1.0, x + 1.0).filter(SpaceChecks.finite)
  override def distance(a: Double, b: Double) = math.abs(a - b)
  override def project(x: Double) =
    if x.isNaN then 0.0
    else if x == Double.PositiveInfinity then Double.MaxValue
    else if x == Double.NegativeInfinity then -Double.MaxValue
    else x

final case class IntervalSpace(min: Double, max: Double, sample: () => Double = () => 0.5) extends Space[Double]:
  require(SpaceChecks.finite(min) && SpaceChecks.finite(max) && min <= max, "interval endpoints must be finite and ordered")
  def description = s"Floating-point interval [$min,$max]"
  def shape = s"Interval[$min,$max]"
  def invariants = List(SpaceChecks.invariant[Double]("interval bounds", x => s"$x is outside [$min,$max]")(x => SpaceChecks.finite(x) && x >= min && x <= max))
  def generate() = project(min + (max - min) * SpaceChecks.clamp(sample(), 0.0, 1.0))
  override def neighbors(x: Double) =
    val step = math.max(math.ulp(project(x)), (max - min) / 100.0)
    LazyList(project(x - step), project(x + step)).distinct
  override def distance(a: Double, b: Double) = math.abs(a - b)
  override def project(x: Double) = if x.isNaN then min else SpaceChecks.clamp(x, min, max)

final case class BitsetSpace(size: Int) extends Space[BitSet]:
  require(size >= 0, "size must be non-negative")
  def description = s"Bitmasks of width $size"
  def shape = s"{0,1}^$size"
  def invariants = List(SpaceChecks.invariant[BitSet]("bit indices", _ => "bit index outside width")(_.forall(i => i >= 0 && i < size)))
  def generate() = BitSet.empty
  override def enumerate =
    if size <= 20 then LazyList.range(0, 1 << size).map(mask => BitSet.fromSpecific((0 until size).filter(i => (mask & (1 << i)) != 0)))
    else LazyList.empty
  override def neighbors(bits: BitSet) = LazyList.from(0 until size).map(i => if bits(i) then bits - i else bits + i)
  override def distance(a: BitSet, b: BitSet) = ((a diff b).size + (b diff a).size).toDouble
  override def project(bits: BitSet) = bits.filter(i => i >= 0 && i < size)

final case class CategoricalSpace[A](values: Vector[A]) extends Space[A]:
  require(values.nonEmpty, "a categorical space needs at least one value")
  private val categories = values.distinct
  def description = s"Categorical enumeration of ${categories.size} values"
  def shape = s"Enum[${categories.mkString(",")}]"
  def invariants = List(SpaceChecks.invariant[A]("category membership", x => s"$x is not allowed")(categories.contains))
  def generate() = categories.head
  override def enumerate = LazyList.from(categories)
  override def neighbors(x: A) = LazyList.from(categories.filterNot(_ == x))
  override def distance(a: A, b: A) = if a == b then 0.0 else 1.0
  override def project(x: A) = if categories.contains(x) then x else categories.head

final case class FixedLengthVectorSpace[A](elementSpace: Space[A], length: Int) extends Space[Vector[A]]:
  require(length >= 0, "length must be non-negative")
  def description = s"Fixed-length vectors over ${elementSpace.description}"
  def shape = s"Vector[$length] of (${elementSpace.shape})"
  def invariants = List(
    SpaceChecks.invariant[Vector[A]]("fixed length", x => s"expected $length elements, got ${x.length}")(_.length == length),
    SpaceChecks.invariant[Vector[A]]("element membership", _ => "one or more elements are invalid")(_.forall(elementSpace.contains))
  )
  def generate() = Vector.fill(length)(elementSpace.generate())
  override def enumerate =
    def product(n: Int): LazyList[Vector[A]] = if n == 0 then LazyList(Vector.empty) else for xs <- product(n - 1); x <- elementSpace.enumerate yield xs :+ x
    product(length)
  override def neighbors(xs: Vector[A]) = LazyList.from(xs.indices).flatMap(i => elementSpace.neighbors(xs(i)).map(x => xs.updated(i, x)))
  override def distance(a: Vector[A], b: Vector[A]) = if a.length != b.length then Double.PositiveInfinity else math.sqrt(a.indices.map(i => math.pow(elementSpace.distance(a(i), b(i)), 2)).sum)
  override def project(xs: Vector[A]) = xs.take(length).map(elementSpace.project).padTo(length, elementSpace.generate())

final case class DynamicSequenceSpace[A](elementSpace: Space[A], minLength: Int = 0, maxLength: Int = 100) extends Space[Vector[A]]:
  require(minLength >= 0 && maxLength >= minLength, "invalid length bounds")
  def description = s"Sequences over ${elementSpace.description}"
  def shape = s"Vector[$minLength..$maxLength] of (${elementSpace.shape})"
  def invariants = List(
    SpaceChecks.invariant[Vector[A]]("length bounds", _ => "length outside bounds")(x => x.length >= minLength && x.length <= maxLength),
    SpaceChecks.invariant[Vector[A]]("element membership", _ => "one or more elements are invalid")(_.forall(elementSpace.contains))
  )
  def generate() = Vector.fill(minLength)(elementSpace.generate())
  override def neighbors(xs: Vector[A]) =
    LazyList.from(xs.indices).flatMap(i => elementSpace.neighbors(xs(i)).map(x => xs.updated(i, x))) #:::
      (if xs.length < maxLength then LazyList(xs :+ elementSpace.generate()) else LazyList.empty) #:::
      (if xs.length > minLength then LazyList(xs.dropRight(1)) else LazyList.empty)
  override def distance(a: Vector[A], b: Vector[A]) = a.zip(b).map(elementSpace.distance).sum + math.abs(a.length - b.length)
  override def project(xs: Vector[A]) = xs.take(maxLength).map(elementSpace.project).padTo(minLength, elementSpace.generate())

final case class DistinctSequenceSpace[A](elementSpace: Space[A], minLength: Int = 0, maxLength: Int = 100) extends Space[Vector[A]]:
  private val sequence = DynamicSequenceSpace(elementSpace, minLength, maxLength)
  def description = "Distinct element sequences"
  def shape = s"Distinct[${sequence.shape}]"
  def invariants = sequence.invariants :+ SpaceChecks.invariant[Vector[A]]("distinct elements", _ => "elements repeat")(x => x.distinct.length == x.length)
  def generate() = sequence.generate().distinct
  override def enumerate = sequence.enumerate.filter(contains)
  override def neighbors(xs: Vector[A]) = sequence.neighbors(xs).filter(contains)
  override def distance(a: Vector[A], b: Vector[A]) = sequence.distance(a, b)
  override def project(xs: Vector[A]) = sequence.project(xs).distinct

final case class PermutationSpace[A](elements: Vector[A]) extends Space[Vector[A]]:
  require(elements.distinct.length == elements.length, "permutation elements must be distinct")
  def description = s"Permutations of ${elements.size} elements"
  def shape = s"S_${elements.size}"
  def invariants = List(SpaceChecks.invariant[Vector[A]]("permutation", _ => "not a permutation")(x => x.length == elements.length && x.toSet == elements.toSet))
  def generate() = elements
  override def enumerate = LazyList.from(elements.permutations)
  override def neighbors(xs: Vector[A]) = LazyList.from(0 until math.max(0, xs.length - 1)).map(i => xs.updated(i, xs(i + 1)).updated(i + 1, xs(i)))
  override def distance(a: Vector[A], b: Vector[A]) = a.zip(b).count(_ != _).toDouble
  override def project(xs: Vector[A]) = xs.filter(elements.contains).distinct ++ elements.filterNot(xs.contains)

final case class SortedSequenceSpace[A](elementSpace: Space[A], minLength: Int = 0, maxLength: Int = 100)(using ordering: Ordering[A]) extends Space[Vector[A]]:
  private val sequence = DynamicSequenceSpace(elementSpace, minLength, maxLength)
  def description = "Ordered / sorted sequences"
  def shape = s"Sorted[${sequence.shape}]"
  def invariants = sequence.invariants :+ SpaceChecks.invariant[Vector[A]]("sorted order", _ => "not sorted")(x => x.zip(x.drop(1)).forall(ordering.lteq))
  def generate() = sequence.generate().sorted
  override def neighbors(xs: Vector[A]) = sequence.neighbors(xs).map(_.sorted).distinct
  override def distance(a: Vector[A], b: Vector[A]) = sequence.distance(a, b)
  override def project(xs: Vector[A]) = sequence.project(xs).sorted

final case class MultisetSpace[A](elementSpace: Space[A], maxSize: Int = 100) extends Space[Map[A, Int]]:
  require(maxSize >= 0, "maxSize must be non-negative")
  def description = "Multisets / bags"
  def shape = s"Bag[0..$maxSize] of (${elementSpace.shape})"
  def invariants = List(SpaceChecks.invariant[Map[A,Int]]("multiplicities", _ => "invalid key, count, or total size")(m => m.forall((a, n) => elementSpace.contains(a) && n > 0) && m.values.sum <= maxSize))
  def generate() = Map.empty
  override def distance(a: Map[A, Int], b: Map[A, Int]) = (a.keySet ++ b.keySet).map(k => math.abs(a.getOrElse(k, 0) - b.getOrElse(k, 0))).sum.toDouble
  override def project(m: Map[A, Int]) = m.iterator.filter((a, n) => elementSpace.contains(a) && n > 0).foldLeft(Map.empty[A, Int]) { case (acc, (a, n)) => if acc.values.sum >= maxSize then acc else acc.updated(a, math.min(n, maxSize - acc.values.sum)) }

type Matrix = Vector[Vector[Double]]

final case class DenseMatrixSpace(rows: Int, columns: Int, elementSpace: Space[Double] = RealSpace()) extends Space[Matrix]:
  require(rows >= 0 && columns >= 0, "matrix dimensions must be non-negative")
  def description = s"Dense $rows by $columns matrices"
  def shape = s"Matrix[$rows,$columns] of (${elementSpace.shape})"
  def invariants = List(
    SpaceChecks.invariant[Matrix]("matrix shape", _ => "wrong matrix shape")(SpaceChecks.matrixShape(_, rows, columns)),
    SpaceChecks.invariant[Matrix]("matrix elements", _ => "one or more entries are invalid")(_.forall(_.forall(elementSpace.contains)))
  )
  def generate() = Vector.fill(rows, columns)(elementSpace.generate())
  override def neighbors(a: Matrix) = LazyList.from(a.indices).flatMap(i => LazyList.from(a(i).indices).flatMap(j => elementSpace.neighbors(a(i)(j)).map(x => a.updated(i, a(i).updated(j, x)))))
  override def distance(a: Matrix, b: Matrix) = if !SpaceChecks.matrixShape(a, rows, columns) || !SpaceChecks.matrixShape(b, rows, columns) then Double.PositiveInfinity else SpaceChecks.euclidean(a.flatten, b.flatten)
  override def project(a: Matrix) = Vector.tabulate(rows, columns)((i, j) => elementSpace.project(a.lift(i).flatMap(_.lift(j)).getOrElse(elementSpace.generate())))

final case class SquareMatrixSpace(size: Int, elementSpace: Space[Double] = RealSpace()) extends Space[Matrix]:
  private val matrix = DenseMatrixSpace(size, size, elementSpace)
  def description = s"Square $size by $size matrices"
  def shape = s"SquareMatrix[$size]"
  def invariants = matrix.invariants
  def generate() = matrix.generate()
  override def neighbors(a: Matrix) = matrix.neighbors(a)
  override def distance(a: Matrix, b: Matrix) = matrix.distance(a, b)
  override def project(a: Matrix) = matrix.project(a)

final case class InvertibleMatrixSpace(size: Int, epsilon: Double = 1e-10) extends Space[Matrix]:
  private val square = SquareMatrixSpace(size)
  private val identity = Vector.tabulate(size, size)((i, j) => if i == j then 1.0 else 0.0)
  def description = s"Invertible $size by $size matrices"
  def shape = s"GL_$size"
  def invariants = square.invariants :+ SpaceChecks.invariant[Matrix]("full rank", _ => "matrix is singular")(SpaceChecks.rank(_, epsilon) == size)
  def generate() = identity
  override def neighbors(a: Matrix) = square.neighbors(a).filter(contains)
  override def distance(a: Matrix, b: Matrix) = square.distance(a, b)
  override def project(a: Matrix) = if contains(a) then a else identity

final case class SymmetricMatrixSpace(size: Int, elementSpace: Space[Double] = RealSpace(), epsilon: Double = 1e-10) extends Space[Matrix]:
  private val square = SquareMatrixSpace(size, elementSpace)
  def description = s"Symmetric $size by $size matrices"
  def shape = s"SymmetricMatrix[$size]"
  def invariants = square.invariants :+ SpaceChecks.invariant[Matrix]("symmetry", _ => "matrix is not symmetric")(a => a.indices.forall(i => a.indices.forall(j => math.abs(a(i)(j) - a(j)(i)) <= epsilon)))
  def generate() = square.generate()
  override def neighbors(a: Matrix) = square.neighbors(a).map(project).distinct
  override def distance(a: Matrix, b: Matrix) = square.distance(a, b)
  override def project(a: Matrix) =
    val p = square.project(a)
    Vector.tabulate(size, size)((i, j) => elementSpace.project((p(i)(j) + p(j)(i)) / 2.0))

final case class SparseMatrix(rows: Int, columns: Int, entries: Map[(Int, Int), Double]):
  def apply(row: Int, column: Int): Double = entries.getOrElse((row, column), 0.0)
  def dense: Matrix = Vector.tabulate(rows, columns)(apply)

final case class SparseMatrixSpace(rows: Int, columns: Int, maxNonZero: Int, elementSpace: Space[Double] = RealSpace()) extends Space[SparseMatrix]:
  require(rows >= 0 && columns >= 0 && maxNonZero >= 0, "dimensions and maxNonZero must be non-negative")
  def description = s"Sparse $rows by $columns matrices"
  def shape = s"SparseMatrix[$rows,$columns;$maxNonZero]"
  def invariants = List(SpaceChecks.invariant[SparseMatrix]("sparse structure", _ => "invalid sparse matrix")(a =>
    a.rows == rows && a.columns == columns && a.entries.size <= maxNonZero &&
      a.entries.forall { case ((i, j), x) => i >= 0 && i < rows && j >= 0 && j < columns && x != 0.0 && elementSpace.contains(x) }
  ))
  def generate() = SparseMatrix(rows, columns, Map.empty)
  override def distance(a: SparseMatrix, b: SparseMatrix) = SpaceChecks.euclidean(a.dense.flatten, b.dense.flatten)
  override def project(a: SparseMatrix) = SparseMatrix(rows, columns, a.entries.iterator.filter { case ((i, j), x) => i >= 0 && i < rows && j >= 0 && j < columns && x != 0.0 }.take(maxNonZero).map((k, x) => k -> elementSpace.project(x)).toMap)

final case class RowStochasticMatrixSpace(size: Int, epsilon: Double = 1e-9) extends Space[Matrix]:
  private val square = SquareMatrixSpace(size, IntervalSpace(0.0, 1.0))
  def description = s"Row-stochastic $size by $size matrices"
  def shape = s"MarkovMatrix[$size]"
  def invariants = square.invariants :+ SpaceChecks.invariant[Matrix]("row stochastic", _ => "a row does not sum to one")(_.forall(row => math.abs(row.sum - 1.0) <= epsilon))
  def generate() = if size == 0 then Vector.empty else Vector.fill(size, size)(1.0 / size)
  override def distance(a: Matrix, b: Matrix) = square.distance(a, b)
  override def project(a: Matrix) = square.project(a).map { row =>
    val positive = row.map(math.max(0.0, _)); val sum = positive.sum
    if sum == 0.0 && size > 0 then Vector.fill(size)(1.0 / size) else positive.map(_ / sum)
  }

final case class OrthogonalMatrixSpace(size: Int, epsilon: Double = 1e-9) extends Space[Matrix]:
  private val square = SquareMatrixSpace(size)
  private val identity = Vector.tabulate(size, size)((i, j) => if i == j then 1.0 else 0.0)
  private def orthogonal(a: Matrix) = SpaceChecks.matrixShape(a, size, size) && a.indices.forall(i => a.indices.forall(j =>
    math.abs(a.indices.map(k => a(k)(i) * a(k)(j)).sum - (if i == j then 1.0 else 0.0)) <= epsilon
  ))
  def description = s"Orthogonal $size by $size real matrices"
  def shape = s"O($size)"
  def invariants = square.invariants :+ SpaceChecks.invariant[Matrix]("orthogonality", _ => "columns are not orthonormal")(orthogonal)
  def generate() = identity
  override def distance(a: Matrix, b: Matrix) = square.distance(a, b)
  override def project(a: Matrix) = if orthogonal(a) then a else identity

final case class UnitaryMatrixSpace(size: Int, epsilon: Double = 1e-9) extends Space[Matrix]:
  private val orthogonal = OrthogonalMatrixSpace(size, epsilon)
  def description = "Real unitary (orthogonal) matrices"
  def shape = orthogonal.shape
  def invariants = orthogonal.invariants
  def generate() = orthogonal.generate()
  override def distance(a: Matrix, b: Matrix) = orthogonal.distance(a, b)
  override def project(a: Matrix) = orthogonal.project(a)

final case class Tensor(dimensions: Vector[Int], values: Vector[Double])

final case class TensorSpace(dimensions: Vector[Int], elementSpace: Space[Double] = RealSpace()) extends Space[Tensor]:
  require(dimensions.forall(_ >= 0), "tensor dimensions must be non-negative")
  private val count = dimensions.product
  def description = "Multi-dimensional tensors"
  def shape = s"Tensor[${dimensions.mkString("×")}] of (${elementSpace.shape})"
  def invariants = List(
    SpaceChecks.invariant[Tensor]("tensor shape", _ => "wrong tensor shape")(t => t.dimensions == dimensions && t.values.length == count),
    SpaceChecks.invariant[Tensor]("tensor elements", _ => "one or more entries are invalid")(_.values.forall(elementSpace.contains))
  )
  def generate() = Tensor(dimensions, Vector.fill(count)(elementSpace.generate()))
  override def neighbors(t: Tensor) = LazyList.from(t.values.indices).flatMap(i => elementSpace.neighbors(t.values(i)).map(x => t.copy(values = t.values.updated(i, x))))
  override def distance(a: Tensor, b: Tensor) = if a.dimensions != b.dimensions then Double.PositiveInfinity else SpaceChecks.euclidean(a.values, b.values)
  override def project(t: Tensor) = Tensor(dimensions, t.values.take(count).map(elementSpace.project).padTo(count, elementSpace.generate()))

final case class Graph[V](adjacency: Map[V, Set[V]]):
  def vertices: Set[V] = adjacency.keySet ++ adjacency.values.flatten
  def edges: Set[(V, V)] = adjacency.flatMap((u, targets) => targets.map(u -> _)).toSet

final case class GraphSpace[V](vertexSpace: Space[V], directed: Boolean = true, allowSelfLoops: Boolean = true, maxVertices: Int = 100) extends Space[Graph[V]]:
  def description = if directed then "Directed adjacency-list graphs" else "Undirected adjacency-list graphs"
  def shape = s"Graph[0..$maxVertices] over (${vertexSpace.shape})"
  def invariants = List(SpaceChecks.invariant[Graph[V]]("graph structure", _ => "invalid graph")(g =>
    g.vertices.size <= maxVertices && g.vertices.forall(vertexSpace.contains) &&
      (allowSelfLoops || g.edges.forall((u, v) => u != v)) &&
      (directed || g.edges.forall((u, v) => g.adjacency.getOrElse(v, Set.empty).contains(u)))
  ))
  def generate() = Graph(Map.empty)
  override def distance(a: Graph[V], b: Graph[V]) = ((a.vertices diff b.vertices).size + (b.vertices diff a.vertices).size + (a.edges diff b.edges).size + (b.edges diff a.edges).size).toDouble
  override def project(g: Graph[V]) =
    val vertices = g.vertices.filter(vertexSpace.contains).take(maxVertices)
    val edges = g.edges.filter((u, v) => vertices(u) && vertices(v) && (allowSelfLoops || u != v))
    val completed = if directed then edges else edges ++ edges.map(_.swap)
    Graph(vertices.map(v => v -> completed.collect { case (`v`, target) => target }).toMap)

final case class DirectedAcyclicGraphSpace[V](vertexSpace: Space[V], maxVertices: Int = 100) extends Space[Graph[V]]:
  private val graph = GraphSpace(vertexSpace, directed = true, allowSelfLoops = false, maxVertices)
  private def acyclic(g: Graph[V]): Boolean =
    def visit(v: V, active: Set[V], done: Set[V]): Option[Set[V]] =
      if active(v) then None
      else if done(v) then Some(done)
      else g.adjacency.getOrElse(v, Set.empty).foldLeft(Option(done))((result, next) => result.flatMap(d => visit(next, active + v, d))).map(_ + v)
    g.vertices.foldLeft(Option(Set.empty[V]))((result, v) => result.flatMap(d => visit(v, Set.empty, d))).nonEmpty
  def description = "Directed acyclic graphs"
  def shape = s"DAG[0..$maxVertices]"
  def invariants = graph.invariants :+ SpaceChecks.invariant[Graph[V]]("acyclic", _ => "graph contains a cycle")(acyclic)
  def generate() = graph.generate()
  override def distance(a: Graph[V], b: Graph[V]) = graph.distance(a, b)
  override def project(g: Graph[V]) = if contains(g) then g else generate()

final case class Tree[A](value: A, children: Vector[Tree[A]] = Vector.empty):
  def size: Int = 1 + children.map(_.size).sum
  def depth: Int = 1 + children.map(_.depth).maxOption.getOrElse(0)

final case class TreeSpace[A](valueSpace: Space[A], maxDepth: Int = 32, maxNodes: Int = 1000) extends Space[Tree[A]]:
  require(maxDepth >= 1 && maxNodes >= 1, "tree limits must be positive")
  private def valid(t: Tree[A]): Boolean = t.size <= maxNodes && t.depth <= maxDepth && valueSpace.contains(t.value) && t.children.forall(valid)
  def description = "Tree hierarchies / abstract syntax trees"
  def shape = s"Tree[depth≤$maxDepth,nodes≤$maxNodes] of (${valueSpace.shape})"
  def invariants = List(SpaceChecks.invariant[Tree[A]]("tree structure", _ => "invalid or oversized tree")(valid))
  def generate() = Tree[A](valueSpace.generate(), Vector.empty)
  override def distance(a: Tree[A], b: Tree[A]) = valueSpace.distance(a.value, b.value) + math.abs(a.size - b.size)
  override def project(t: Tree[A]) = if valid(t) then t else generate()

final case class WeightedEdge[V, W](from: V, to: V, weight: W)

final case class EdgeTupleSpace[V, W](vertexSpace: Space[V], weightSpace: Space[W], allowSelfLoops: Boolean = true) extends Space[WeightedEdge[V, W]]:
  def description = "Node / edge parameter tuples"
  def shape = s"(${vertexSpace.shape},${vertexSpace.shape},${weightSpace.shape})"
  def invariants = List(SpaceChecks.invariant[WeightedEdge[V,W]]("edge tuple", _ => "invalid edge tuple")(e => vertexSpace.contains(e.from) && vertexSpace.contains(e.to) && weightSpace.contains(e.weight) && (allowSelfLoops || e.from != e.to)))
  def generate() = WeightedEdge(vertexSpace.generate(), vertexSpace.generate(), weightSpace.generate())
  override def distance(a: WeightedEdge[V, W], b: WeightedEdge[V, W]) = vertexSpace.distance(a.from, b.from) + vertexSpace.distance(a.to, b.to) + weightSpace.distance(a.weight, b.weight)
  override def project(e: WeightedEdge[V, W]) = WeightedEdge(vertexSpace.project(e.from), vertexSpace.project(e.to), weightSpace.project(e.weight))

final case class Bipartition[A](left: Set[A], right: Set[A])

final case class BipartitePartitionSpace[A](elementSpace: Space[A], maxElements: Int = 100) extends Space[Bipartition[A]]:
  def description = "Bipartite partitions"
  def shape = s"Bipartition[0..$maxElements] of (${elementSpace.shape})"
  def invariants = List(SpaceChecks.invariant[Bipartition[A]]("bipartition", _ => "partitions overlap or contain invalid elements")(p => p.left.intersect(p.right).isEmpty && p.left.size + p.right.size <= maxElements && (p.left ++ p.right).forall(elementSpace.contains)))
  def generate() = Bipartition(Set.empty, Set.empty)
  override def distance(a: Bipartition[A], b: Bipartition[A]) = ((a.left diff b.left).size + (b.left diff a.left).size + (a.right diff b.right).size + (b.right diff a.right).size).toDouble
  override def project(p: Bipartition[A]) =
    val left = p.left.filter(elementSpace.contains).take(maxElements)
    Bipartition(left, p.right.filter(elementSpace.contains).diff(left).take(maxElements - left.size))

final case class AlphabetStringSpace(alphabet: Vector[Char], minLength: Int = 0, maxLength: Int = 100) extends Space[String]:
  require(alphabet.nonEmpty && minLength >= 0 && maxLength >= minLength, "invalid alphabet or length bounds")
  private val characters = alphabet.distinct
  def description = s"Strings over alphabet {${characters.mkString}}"
  def shape = s"Σ^[$minLength..$maxLength]"
  def invariants = List(
    SpaceChecks.invariant[String]("string length", _ => "length outside bounds")(s => s.length >= minLength && s.length <= maxLength),
    SpaceChecks.invariant[String]("alphabet", _ => "character outside alphabet")(_.forall(characters.contains))
  )
  def generate() = characters.head.toString * minLength
  override def enumerate =
    def words(length: Int): LazyList[String] = if length == 0 then LazyList("") else for prefix <- words(length - 1); c <- LazyList.from(characters) yield prefix + c
    LazyList.range(minLength, maxLength + 1).flatMap(words)
  override def neighbors(s: String) =
    LazyList.from(s.indices).flatMap(i => LazyList.from(characters.filterNot(_ == s(i))).map(c => s.updated(i, c))) #:::
      (if s.length < maxLength then LazyList(s + characters.head) else LazyList.empty) #:::
      (if s.length > minLength then LazyList(s.dropRight(1)) else LazyList.empty)
  override def distance(a: String, b: String) = a.zip(b).count(_ != _).toDouble + math.abs(a.length - b.length)
  override def project(s: String) = s.filter(characters.contains).take(maxLength).padTo(minLength, characters.head)

final case class TokenSequenceSpace[T](tokenSpace: Space[T], minLength: Int = 0, maxLength: Int = 100) extends Space[Vector[T]]:
  private val sequence = DynamicSequenceSpace(tokenSpace, minLength, maxLength)
  def description = "Token sequences"
  def shape = sequence.shape
  def invariants = sequence.invariants
  def generate() = sequence.generate()
  override def neighbors(tokens: Vector[T]) = sequence.neighbors(tokens)
  override def distance(a: Vector[T], b: Vector[T]) = sequence.distance(a, b)
  override def project(tokens: Vector[T]) = sequence.project(tokens)

final case class DictionarySpace[K, V](keySpace: Space[K], valueSpace: Space[V], maxEntries: Int = 100) extends Space[Map[K, V]]:
  require(maxEntries >= 0, "maxEntries must be non-negative")
  def description = "Key-value dictionaries / environment maps"
  def shape = s"Map[${keySpace.shape},${valueSpace.shape};0..$maxEntries]"
  def invariants = List(SpaceChecks.invariant[Map[K,V]]("dictionary", _ => "invalid dictionary")(m => m.size <= maxEntries && m.forall((k, v) => keySpace.contains(k) && valueSpace.contains(v))))
  def generate() = Map.empty
  override def distance(a: Map[K, V], b: Map[K, V]) = (a.keySet ++ b.keySet).map(k => (a.get(k), b.get(k)) match
    case (Some(x), Some(y)) => valueSpace.distance(x, y)
    case _ => 1.0
  ).sum
  override def project(m: Map[K, V]) = m.iterator.filter((k, _) => keySpace.contains(k)).take(maxEntries).map((k, v) => k -> valueSpace.project(v)).toMap

final case class GrammarProduction[N, T](left: N, right: Vector[Either[N, T]])

final case class GrammarProductionSpace[N, T](nonTerminalSpace: Space[N], terminalSpace: Space[T], maxRightLength: Int = 100) extends Space[GrammarProduction[N, T]]:
  def description = "Formal grammar productions"
  def shape = s"${nonTerminalSpace.shape}→(${nonTerminalSpace.shape}|${terminalSpace.shape})*"
  def invariants = List(SpaceChecks.invariant[GrammarProduction[N,T]]("grammar production", _ => "invalid production")(p => nonTerminalSpace.contains(p.left) && p.right.length <= maxRightLength && p.right.forall(_.fold(nonTerminalSpace.contains, terminalSpace.contains))))
  def generate() = GrammarProduction(nonTerminalSpace.generate(), Vector.empty)
  override def distance(a: GrammarProduction[N, T], b: GrammarProduction[N, T]) = nonTerminalSpace.distance(a.left, b.left) + math.abs(a.right.length - b.right.length)
  override def project(p: GrammarProduction[N, T]) = GrammarProduction(nonTerminalSpace.project(p.left), p.right.take(maxRightLength).filter(_.fold(nonTerminalSpace.contains, terminalSpace.contains)))

final case class PrefixFreeSetSpace(alphabet: Vector[Char], maxWords: Int = 100, maxWordLength: Int = 100) extends Space[Set[String]]:
  private val strings = AlphabetStringSpace(alphabet, 0, maxWordLength)
  private def prefixFree(words: Set[String]) = !words.exists(a => words.exists(b => a != b && b.startsWith(a)))
  def description = "Prefix-free / trie path sets"
  def shape = s"PrefixFreeSet[0..$maxWords]"
  def invariants = List(SpaceChecks.invariant[Set[String]]("prefix-free", _ => "invalid words or one word prefixes another")(words => words.size <= maxWords && words.forall(strings.contains) && prefixFree(words)))
  def generate() = Set.empty
  override def distance(a: Set[String], b: Set[String]) = ((a diff b).size + (b diff a).size).toDouble
  override def project(words: Set[String]) = words.filter(strings.contains).toVector.sortBy(_.length).foldLeft(Set.empty[String])((accepted, word) => if accepted.exists(word.startsWith) then accepted else accepted + word).take(maxWords)

final case class Point(coordinates: Vector[Double])

final case class CartesianSpace(axes: Vector[Space[Double]]) extends Space[Point]:
  def description = "Cartesian coordinate tuples"
  def shape = s"(${axes.map(_.shape).mkString("×")})"
  def invariants = List(
    SpaceChecks.invariant[Point]("coordinate dimensions", _ => "wrong coordinate dimensions")(_.coordinates.length == axes.length),
    SpaceChecks.invariant[Point]("coordinate bounds", _ => "invalid coordinate")(p => p.coordinates.indices.forall(i => axes(i).contains(p.coordinates(i))))
  )
  def generate() = Point(axes.map(_.generate()))
  override def neighbors(p: Point) = LazyList.from(p.coordinates.indices).flatMap(i => axes(i).neighbors(p.coordinates(i)).map(x => Point(p.coordinates.updated(i, x))))
  override def distance(a: Point, b: Point) = SpaceChecks.euclidean(a.coordinates, b.coordinates)
  override def project(p: Point) = Point(axes.indices.map(i => axes(i).project(p.coordinates.lift(i).getOrElse(axes(i).generate()))).toVector)

final case class AxisAlignedBoundingBox(min: Point, max: Point)

final case class BoundingBoxSpace(dimensions: Int, coordinateSpace: Space[Double] = RealSpace()) extends Space[AxisAlignedBoundingBox]:
  private val points = CartesianSpace(Vector.fill(dimensions)(coordinateSpace))
  def description = "Axis-aligned bounding boxes"
  def shape = s"AABB[$dimensions]"
  def invariants = List(SpaceChecks.invariant[AxisAlignedBoundingBox]("bounding box", _ => "invalid box corners")(b => points.contains(b.min) && points.contains(b.max) && b.min.coordinates.zip(b.max.coordinates).forall(_ <= _)))
  def generate() =
    val point = points.generate(); AxisAlignedBoundingBox(point, point)
  override def distance(a: AxisAlignedBoundingBox, b: AxisAlignedBoundingBox) = points.distance(a.min, b.min) + points.distance(a.max, b.max)
  override def project(b: AxisAlignedBoundingBox) =
    val p = points.project(b.min); val q = points.project(b.max)
    AxisAlignedBoundingBox(Point(p.coordinates.zip(q.coordinates).map((x, y) => math.min(x, y))), Point(p.coordinates.zip(q.coordinates).map((x, y) => math.max(x, y))))

final case class Polygon(vertices: Vector[Point])

final case class ConvexPolygonSpace(coordinateSpace: Space[Double] = RealSpace(), maxVertices: Int = 100, epsilon: Double = 1e-10) extends Space[Polygon]:
  private def convex(p: Polygon): Boolean =
    if p.vertices.length < 3 || p.vertices.exists(_.coordinates.length != 2) then false
    else
      val crossProducts = p.vertices.indices.map { i =>
        val a = p.vertices(i).coordinates; val b = p.vertices((i + 1) % p.vertices.length).coordinates; val c = p.vertices((i + 2) % p.vertices.length).coordinates
        (b(0) - a(0)) * (c(1) - b(1)) - (b(1) - a(1)) * (c(0) - b(0))
      }.filter(math.abs(_) > epsilon)
      crossProducts.nonEmpty && (crossProducts.forall(_ > 0) || crossProducts.forall(_ < 0))
  private def triangle = Polygon(Vector(Point(Vector(0, 0)), Point(Vector(1, 0)), Point(Vector(0, 1))).map(p => Point(p.coordinates.map(coordinateSpace.project))))
  def description = "Convex planar polygons"
  def shape = s"ConvexPolygon[3..$maxVertices]"
  def invariants = List(SpaceChecks.invariant[Polygon]("convex polygon", _ => "polygon is not valid and convex")(p => p.vertices.length <= maxVertices && p.vertices.forall(_.coordinates.forall(coordinateSpace.contains)) && convex(p)))
  def generate() = triangle
  override def distance(a: Polygon, b: Polygon) = if a.vertices.length != b.vertices.length then Double.PositiveInfinity else a.vertices.zip(b.vertices).map((x, y) => SpaceChecks.euclidean(x.coordinates, y.coordinates)).sum
  override def project(p: Polygon) = if contains(p) then p else triangle

final case class PointCloudSpace(dimensions: Int, coordinateSpace: Space[Double] = RealSpace(), minPoints: Int = 0, maxPoints: Int = 1000) extends Space[Vector[Point]]:
  private val point = CartesianSpace(Vector.fill(dimensions)(coordinateSpace))
  def description = "Spatial point clouds"
  def shape = s"PointCloud[$minPoints..$maxPoints] of (${point.shape})"
  def invariants = List(SpaceChecks.invariant[Vector[Point]]("point cloud", _ => "invalid point cloud")(p => p.length >= minPoints && p.length <= maxPoints && p.forall(point.contains)))
  def generate() = Vector.fill(minPoints)(point.generate())
  override def distance(a: Vector[Point], b: Vector[Point]) = a.zip(b).map(point.distance).sum + math.abs(a.length - b.length)
  override def project(p: Vector[Point]) = p.take(maxPoints).map(point.project).padTo(minPoints, point.generate())

final case class PositionalArgumentsSpace(arguments: Vector[Space[?]]) extends Space[Vector[Any]]:
  private val spaces = arguments.map(_.asInstanceOf[Space[Any]])
  def description = "Positional argument tuples"
  def shape = s"(${spaces.map(_.shape).mkString("×")})"
  def invariants = List(SpaceChecks.invariant[Vector[Any]]("argument tuple", _ => "invalid argument tuple")(x => x.length == spaces.length && x.indices.forall(i => spaces(i).contains(x(i)))))
  def generate() = spaces.map(_.generate())
  override def distance(a: Vector[Any], b: Vector[Any]) = if a.length != spaces.length || b.length != spaces.length then Double.PositiveInfinity else spaces.indices.map(i => spaces(i).distance(a(i), b(i))).sum
  override def project(x: Vector[Any]) = spaces.indices.map(i => x.lift(i).map(spaces(i).project).getOrElse(spaces(i).generate())).toVector

final case class KeywordParameterSpace(parameters: Map[String, Space[?]], required: Set[String] = Set.empty, allowUnknown: Boolean = false) extends Space[Map[String, Any]]:
  private val spaces = parameters.view.mapValues(_.asInstanceOf[Space[Any]]).toMap
  require(required.subsetOf(spaces.keySet), "required keys must be declared")
  def description = "Keyword / option parameter maps"
  def shape = s"Options{${spaces.keys.toVector.sorted.mkString(",")}}"
  def invariants = List(SpaceChecks.invariant[Map[String,Any]]("parameter map", _ => "invalid parameter map")(m => required.subsetOf(m.keySet) && m.forall((k, v) => spaces.get(k).exists(_.contains(v)) || (allowUnknown && !spaces.contains(k)))))
  def generate() = required.map(k => k -> spaces(k).generate()).toMap
  override def distance(a: Map[String, Any], b: Map[String, Any]) = (a.keySet ++ b.keySet).map(k => (a.get(k), b.get(k), spaces.get(k)) match
    case (Some(x), Some(y), Some(space)) => space.distance(x, y)
    case (Some(x), Some(y), None) => if x == y then 0.0 else 1.0
    case _ => 1.0
  ).sum
  override def project(m: Map[String, Any]) = spaces.flatMap((k, space) => m.get(k).map(v => k -> space.project(v)).orElse(if required(k) then Some(k -> space.generate()) else None))

final case class HyperparameterGridSpace[A](axes: Vector[(String, Vector[A])]) extends Space[Map[String, A]]:
  require(axes.map(_._1).distinct.length == axes.length && axes.forall(_._2.nonEmpty), "grid axes need unique names and non-empty values")
  private val axisMap = axes.toMap
  def description = "Bounded hyperparameter grids"
  def shape = axes.map((name, values) => s"$name:${values.size}").mkString("Grid[", "×", "]")
  def invariants = List(SpaceChecks.invariant[Map[String,A]]("grid point", _ => "invalid grid point")(p => p.keySet == axisMap.keySet && p.forall((name, value) => axisMap(name).contains(value))))
  def generate() = axes.map((name, values) => name -> values.head).toMap
  override def enumerate = axes.foldLeft(LazyList(Map.empty[String, A]))((points, axis) => for point <- points; value <- LazyList.from(axis._2) yield point.updated(axis._1, value))
  override def neighbors(p: Map[String, A]) = LazyList.from(axes).flatMap((name, values) => LazyList.from(values.filterNot(_ == p.getOrElse(name, values.head))).map(value => p.updated(name, value)))
  override def distance(a: Map[String, A], b: Map[String, A]) = axes.count((name, _) => a.get(name) != b.get(name)).toDouble
  override def project(p: Map[String, A]) = axes.map((name, values) => name -> p.get(name).filter(values.contains).getOrElse(values.head)).toMap

final case class FunctionSolutionSpace(dimensions: Vector[IntervalSpace], feasible: Vector[Double] => Boolean = _ => true) extends Space[Vector[Double]]:
  private val coordinates = CartesianSpace(dimensions)
  def description = "Function solution / feasible parameter regions"
  def shape = s"Ω⊆R^${dimensions.length}"
  def invariants = List(
    SpaceChecks.invariant[Vector[Double]]("parameter bounds", _ => "invalid parameter vector")(x => coordinates.contains(Point(x))),
    SpaceChecks.invariant[Vector[Double]]("feasibility", _ => "infeasible parameter vector")(feasible)
  )
  def generate() =
    val point = dimensions.map(_.generate())
    if feasible(point) then point else throw new IllegalStateException("generated point is infeasible")
  override def neighbors(x: Vector[Double]) = coordinates.neighbors(Point(x)).map(_.coordinates).filter(feasible)
  override def distance(a: Vector[Double], b: Vector[Double]) = SpaceChecks.euclidean(a, b)
  override def project(x: Vector[Double]) =
    val bounded = coordinates.project(Point(x)).coordinates
    if feasible(bounded) then bounded else generate()

final case class ActionAlphabetSpace[A](actions: Vector[A]) extends Space[A]:
  private val categories = CategoricalSpace(actions)
  def description = "Action / transition alphabets"
  def shape = categories.shape
  def invariants = categories.invariants
  def generate() = categories.generate()
  override def enumerate = categories.enumerate
  override def neighbors(a: A) = categories.neighbors(a)
  override def distance(a: A, b: A) = categories.distance(a, b)
  override def project(a: A) = categories.project(a)

final case class FrontierSpace[S](stateSpace: Space[S], maxSize: Int = 10000) extends Space[Set[S]]:
  def description = s"Open / frontier subsets of ${stateSpace.description}"
  def shape = s"Open[0..$maxSize]⊆(${stateSpace.shape})"
  def invariants = List(SpaceChecks.invariant[Set[S]]("frontier set", _ => "invalid frontier set")(x => x.size <= maxSize && x.forall(stateSpace.contains)))
  def generate() = Set.empty
  override def distance(a: Set[S], b: Set[S]) = ((a diff b).size + (b diff a).size).toDouble
  override def project(s: Set[S]) = s.filter(stateSpace.contains).take(maxSize)

final case class ExploredSpace[S](stateSpace: Space[S], maxSize: Int = 10000) extends Space[Set[S]]:
  def description = s"Closed / explored subsets of ${stateSpace.description}"
  def shape = s"Closed[0..$maxSize]⊆(${stateSpace.shape})"
  def invariants = List(SpaceChecks.invariant[Set[S]]("explored set", _ => "invalid explored set")(x => x.size <= maxSize && x.forall(stateSpace.contains)))
  def generate() = Set.empty
  override def distance(a: Set[S], b: Set[S]) = ((a diff b).size + (b diff a).size).toDouble
  override def project(s: Set[S]) = s.filter(stateSpace.contains).take(maxSize)

type IntegerSet = IntegerSpace
type NaturalNumberSet = NaturalNumberSpace
type RealNumberSpace = RealSpace
type FloatingPointInterval = IntervalSpace
type FixedLengthArraySpace[A] = FixedLengthVectorSpace[A]
type UniqueSequenceSpace[A] = DistinctSequenceSpace[A]
type MatrixSpace = DenseMatrixSpace
type DAGSpace[V] = DirectedAcyclicGraphSpace[V]
type AABBSpace = BoundingBoxSpace
type FeasibleParameterSpace = FunctionSolutionSpace
