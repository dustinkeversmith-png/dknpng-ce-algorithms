
class TypeTests extends munit.FunSuite:
  test("base type pack registers sizes and lambda operators into an empty registry"):
    val emptyRegistry = new TypeRegistry()
    assert(emptyRegistry.sizes.isEmpty)
    assert(emptyRegistry.operators.isEmpty)

    val registry = new BaseTypes().registerAll()

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
    value.operator("=")(registry.caster.cast("double", 6.0))

    val ten = value.operator("+")(registry.caster.cast("double", 4.0))
    assert(registry.caster.retrieve("byte", ten.operator("equals")(registry.caster.cast("double", 10.0))) == 1.0)

    val leftStructure = new Value(
      "leftStructure",
      Vector.empty,
      Map("x" -> "double", "count" -> "int")
    )
    leftStructure.registry = registry
    leftStructure.index_fields()
    leftStructure.allocate()
    leftStructure.reference_member("x").operator("=")(registry.caster.cast("double", 2.5))
    leftStructure.reference_member("count").operator("=")(registry.caster.cast("int", 3.0))

    val rightStructure = new Value(
      "rightStructure",
      Vector.empty,
      Map("x" -> "double", "count" -> "int")
    )
    rightStructure.registry = registry
    rightStructure.index_fields()
    rightStructure.allocate()
    rightStructure.reference_member("x").operator("=")(registry.caster.cast("double", 1.5))
    rightStructure.reference_member("count").operator("=")(registry.caster.cast("int", 4.0))

    val combinedX = leftStructure.reference_member("x").operator("+")(rightStructure.reference_member("x"))
    val combinedCount = leftStructure.reference_member("count").operator("+")(rightStructure.reference_member("count"))
    assert(registry.caster.retrieve("byte", combinedX.operator("equals")(registry.caster.cast("double", 4.0))) == 1.0)
    assert(registry.caster.retrieve("byte", combinedCount.operator("equals")(registry.caster.cast("int", 7.0))) == 1.0)

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

    assert(particle.equals(sameParticle))
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

    val valueRegistry = new BaseTypes().registerAll()
    position.registry = valueRegistry
    this_value_type.registry = valueRegistry
    position.index_fields()
    position.allocate()
    this_value_type.index_fields()
    this_value_type.allocate()

    // Basic indexing and assignment
    // position[0] = 1;
    // position[1] = 2;
    // position[2] = 3;
    position.reference_element(Array(0)).operator("=")(valueRegistry.caster.cast("int", 1.0))
    position.reference_element(Array(1)).operator("=")(valueRegistry.caster.cast("int", 2.0))
    position.reference_element(Array(2)).operator("=")(valueRegistry.caster.cast("int", 3.0))

    // Multidimensional iteration
    val iter = position.iterator();
    // This should return the value as the value type on next etc 
    val firstPosition: Value = iter.next().value()
    val secondPosition: Value = iter.next().value()
    val thirdPosition: Value = iter.next().value()
    assert(valueRegistry.caster.retrieve("int", firstPosition) == 1.0)
    assert(valueRegistry.caster.retrieve("int", secondPosition) == 2.0)
    assert(valueRegistry.caster.retrieve("int", thirdPosition) == 3.0)

    // Multidimensional and nested assignment
    // this_value_type[0,0,0,0]["id"] = 2;
    // this_value_type[0,0,0,0]["mass"] = 3.0;
    val selectedValue = this_value_type.reference_element(Array(0, 0, 0, 0))
    selectedValue.reference_member("id").operator("=")(valueRegistry.caster.cast("long", 2.0))
    selectedValue.reference_member("mass").operator("=")(valueRegistry.caster.cast("double", 3.0))
    assert(selectedValue.shape.isEmpty)
    assert(selectedValue.fields.nonEmpty)
    assert(selectedValue.fields.contains("id"))
    assert(selectedValue.fields.contains("mass"))
    assert(selectedValue.index.contains("id"))
    assert(selectedValue.index.contains("mass"))
    assert(selectedValue.fields.keysIterator.hasNext)
    assert(selectedValue.fields.keysIterator.filter(fieldName => selectedValue.index.contains(fieldName)).hasNext)

    // Iterating values example
    // val iter =  this_value_type[0,0,0,0].iterator()
    val selectedIterator = selectedValue.iterator()
    assert(selectedIterator.hasNext)
    // .value resolves the iterator and will return the nest type as a Value 
    // Value id = iter.value()
    val id: Value = selectedIterator.next().value()
    // Return values like this ensures that we never have any types we just use the specified types.
    // Value mass = iter.next().value()
    val mass: Value = selectedIterator.next().value()
    assert(valueRegistry.caster.retrieve("long", id) == 2.0)
    assert(valueRegistry.caster.retrieve("double", mass) == 3.0)

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

    val selectedDimensionValues = this_value_type.iterate_dimension(Array(0, 0, 0, 0), 3).toVector
    assert(selectedDimensionValues.length == 2)
    assert(selectedDimensionValues.head.index("id").offset == 0L)
    assert(selectedDimensionValues.last.index("id").offset == 28L)

    val positionAndMass = this_value_type.iterate_value("position", "mass").toVector
    assert(positionAndMass.length == 32)
    assert(positionAndMass.head.offset == 8L)
    assert(positionAndMass.last.offset == 436L)

  test("nested_structure_operator_resolution"):

    val positionType = new ValueType(
      "Position",
      Vector(3),
      Map("value" -> "int")
    )

    // nested particle value type declaring a add operator between the two 
    val valueRegistry = new BaseTypes().registerAll()

    // val leftStructure = new Value(
    //   "particle",
    //   Vector.empty,
    //   type=positionType
    // )
    // val rightStructure = new Value(
    //   "particle",
    //   Vector.empty,
    //   type=positionType
    // )
    val leftStructure = new Value("particle", positionType)
    val rightStructure = new Value("particle", positionType)

    rightStructure.registry = valueRegistry
    leftStructure.registry = valueRegistry

    
    leftStructure.index_fields()
    leftStructure.allocate()

    rightStructure.index_fields()
    rightStructure.allocate()

    leftStructure.reference_element(Array(0)).operator("=")(valueRegistry.caster.cast("int", 1.0))
    leftStructure.reference_element(Array(1)).operator("=")(valueRegistry.caster.cast("int", 2.0))
    leftStructure.reference_element(Array(2)).operator("=")(valueRegistry.caster.cast("int", 3.0))

    rightStructure.reference_element(Array(0)).operator("=")(valueRegistry.caster.cast("int", 4.0))
    rightStructure.reference_element(Array(1)).operator("=")(valueRegistry.caster.cast("int", 5.0))
    rightStructure.reference_element(Array(2)).operator("=")(valueRegistry.caster.cast("int", 6.0))

    val assignOperator: OperatorFunction = (id, a, arguments) =>
      // val right = valueRegistry.caster.retrieve(id.arguments("b"), arguments(0))
      // valueRegistry.caster.insert(id.arguments("a"), a, right)
      val right = arguments(0)
      a.reference_element(Array(0)).operator("=")(right.reference_element(Array(0)))
      a.reference_element(Array(1)).operator("=")(right.reference_element(Array(1)))
      a.reference_element(Array(2)).operator("=")(right.reference_element(Array(2)))
      a

    val addOperator: OperatorFunction = (id, x, arguments) =>
      // Value l = arguments(0)
      // Value r = arguments(1)

      // Value return = "of position type"
      
      // val left = valueRegistry.caster.retrieve(id.arguments("a"), arguments(0))
      // val right = valueRegistry.caster.retrieve(id.arguments("b"), arguments(1))
      val left = x
      val right = arguments(0)
      val result = new Value("position_result", positionType)
      result.registry = x.registry
      result.index_fields()
      result.allocate()



      // For each of our values since this function is keenly aware that position has 3 things inside of it.
      // value.operator("+")(l.reference_element(Array(0)), r.reference_element(Array(0)))
      val added0 = left.reference_element(Array(0)).operator("+")(right.reference_element(Array(0)))
      val added1 = left.reference_element(Array(1)).operator("+")(right.reference_element(Array(1)))
      val added2 = left.reference_element(Array(2)).operator("+")(right.reference_element(Array(2)))
      result.reference_element(Array(0)).operator("=")(added0)
      result.reference_element(Array(1)).operator("=")(added1)
      result.reference_element(Array(2)).operator("=")(added2)
      ///
      result

    val argumentTypes = Map("a" -> "Position", "b" -> "Position")
    valueRegistry.register_operator("Position", FunctionalId("=", argumentTypes), assignOperator)
    valueRegistry.register_operator("Position", FunctionalId("+", argumentTypes), addOperator)

    val result = leftStructure.operator("+")(rightStructure)
    assert(result.t == "Position")
    assert(valueRegistry.caster.retrieve("int", result.reference_element(Array(0))) == 5.0)
    assert(valueRegistry.caster.retrieve("int", result.reference_element(Array(1))) == 7.0)
    assert(valueRegistry.caster.retrieve("int", result.reference_element(Array(2))) == 9.0)

    leftStructure.operator("=")(rightStructure)
    assert(valueRegistry.caster.retrieve("int", leftStructure.reference_element(Array(0))) == 4.0)
    assert(valueRegistry.caster.retrieve("int", leftStructure.reference_element(Array(1))) == 5.0)
    assert(valueRegistry.caster.retrieve("int", leftStructure.reference_element(Array(2))) == 6.0)


  

    // Then register a new operator on our position types

    




