
class TypeTests extends munit.FunSuite:
  test("base type pack registers sizes and lambda operators into an empty registry"):
    val emptyRegistry = new TypeRegistry()
    assert(emptyRegistry.sizes.isEmpty)
    assert(emptyRegistry.operators.isEmpty)

    val baseTypes = new BaseTypes()
    val registry = baseTypes.registerAll()

    assert(registry.sizes("byte") == 1L)
    assert(registry.sizes("short") == 2L)
    assert(registry.sizes("int") == 4L)
    assert(registry.sizes("long") == 8L)
    assert(registry.sizes("float") == 4L)
    assert(registry.sizes("double") == 8L)
    assert(registry.operators("double").operator_set.contains("+_double_double"))
    assert(registry.operators("double").operator_set.contains("=_double_double"))

    val value = new Value("registered", Vector.empty, Map("value" -> "double"))
    value.registry = registry
    value.index_fields()
    value.allocate()
    value.operators("=")(6.0)

    assert(value.operators("+")(4.0).operators("equals")(10.0).truth())

    val leftStructure = new Value(
      "leftStructure",
      Vector.empty,
      Map("x" -> "double", "count" -> "int")
    )
    leftStructure.registry = registry
    leftStructure.index_fields()
    leftStructure.allocate()
    leftStructure("x").operators("=")(2.5)
    leftStructure("count").operators("=")(3)

    val rightStructure = new Value(
      "rightStructure",
      Vector.empty,
      Map("x" -> "double", "count" -> "int")
    )
    rightStructure.registry = registry
    rightStructure.index_fields()
    rightStructure.allocate()
    rightStructure("x").operators("=")(1.5)
    rightStructure("count").operators("=")(4)

    val combinedStructure = leftStructure.operators("+")(rightStructure)
    assert(combinedStructure("x").operators("equals")(4.0).truth())
    assert(combinedStructure("count").operators("equals")(7).truth())

  test("type creation and base functional iteration type and size natures"):
    val positionType = new ValueType(
      "Array",
      Vector(3),
      Map("value" -> "int")
    )

    val particle = new Value(
      "Particle",
      Vector(5),
      Map(
        "id" -> "long",
        "mass" -> "double",
        "position" -> positionType
      )
    )

    particle.index_fields()

    assert(particle.fields("mass").t == "double")
    assert(particle.element_size == 28L)
    assert(particle.total_size == 140L)
    assert(particle.tail(0) == 28L)
    assert(particle.index("[2].mass").offset == 64L)
    assert(particle.index("[2].position[1].value").offset == 76L)
    assert(particle.index("[2].position[1].value").length == 4L)

    particle.allocate()
    assert(particle.memory.length == 140)

    val sameParticle = new Value(
      "Particle",
      Vector(5),
      Map(
        "id" -> "long",
        "mass" -> "double",
        "position" -> new ValueType("Array", Vector(3), Map("value" -> "int"))
      )
    )

    assert(particle == sameParticle)
    assert(particle.hashCode() == sameParticle.hashCode())

  test("multidimensional shape and internal field iteration"):
    val positionType = new ValueType(
      "Array",
      Vector(3),
      Map("value" -> "int")
    )


    

    val this_value_type = new Value(
      "ParticleGrid",
      Vector(2, 2, 2, 2),
      Map(
        "id" -> "long",
        "mass" -> "double",
        "position" -> positionType
      )
    )

    val position = new Value(
      "position",
      Vector(3),
      Map("value" -> "int")
    )

    // Basic indexing and assignment
    position[0] = 1;
    position[1] = 2;
    position[2] = 3;

    // Multidimensional iteration
    val iter = position.iterator();
    // This should return the value as the value type on next etc 

    // Multidimensional and nested assignment
    this_value_type[0,0,0,0]["id"] = 2;
    this_value_type[0,0,0,0]["mass"] = 3.0;

    // Iterating values example
    val iter =  this_value_type[0,0,0,0].iterator()
    // .value resolves the iterator and will return the nest type as a Value 
    Value id = iter.value() 
    // Return values like this ensures that we never have any types we just use the specified types.
    Value mass = iter.next().value()

    this_value_type.index_fields()

    assert(this_value_type.element_size == 28L)
    assert(this_value_type.total_size == 448L)
    assert(this_value_type.tails == Vector(224L, 112L, 56L, 28L))

    val selectedDimension = this_value_type.index_dimension(1, 0, 1, 0)
    assert(selectedDimension.offset == 280L)
    assert(selectedDimension.length == 28L)

    val selectedFields = this_value_type.index_value(Seq(1, 0, 1, 0), "position", "mass")
    assert(selectedFields(0).offset == 296L)
    assert(selectedFields(0).length == 12L)
    assert(selectedFields(1).offset == 288L)
    assert(selectedFields(1).length == 8L)

    val allDimensions = this_value_type.iterate_dimension().toVector
    assert(allDimensions.length == 16)
    assert(allDimensions.head.offset == 0L)
    assert(allDimensions.last.offset == 420L)

    val positionAndMass = this_value_type.iterate_value("position", "mass").toVector
    assert(positionAndMass.length == 32)
    assert(positionAndMass.head.offset == 8L)
    assert(positionAndMass.last.offset == 436L)

