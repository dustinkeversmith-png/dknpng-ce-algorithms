// I added test_base_ops right in here for testing the base operations between all the basic types using Value that has type of any base type.

class BaseOperatorTests extends munit.FunSuite:
  test("register base overloads with FunctionalId and run them on fields of a new type"):
    // Registers all of the types.
    val registry = new BaseTypes().registerAll()

    assert(registry.sizes("byte") == 1L)
    assert(registry.sizes("short") == 2L)
    assert(registry.sizes("int") == 4L)
    assert(registry.sizes("long") == 8L)
    assert(registry.sizes("float") == 4L)
    assert(registry.sizes("double") == 8L)

    val integerOperators = registry.operators("int")
    val (registeredId, _) = integerOperators.operator_set("add_int_int")
    assert(registeredId == FunctionalId("add", Map("a" -> "int", "b" -> "int")))

    val pairType = new Value(
      "Pair",
      Vector.empty,
      Map(
        "left" -> "int",
        "right" -> "int"
      )
    )

    // Sets registry indexes the fields and then sets the values.
    pairType.registry = registry
    pairType.index_fields()
    pairType.allocate()

    pairType.reference_member("left").operator("=")(registry.caster.cast("int", 4.0))
    pairType.reference_member("right").operator("=")(registry.caster.cast("int", 6.0))

    val addedFields = integerOperators.operator(
      "add",
      Vector(pairType.reference_member("left"), pairType.reference_member("right"))
    )

    val addedEqualsTen = addedFields.operator("equals")(registry.caster.cast("int", 10.0))
    assert(registry.caster.retrieve("byte", addedEqualsTen) == 1.0)

    pairType.reference_member("left").operator("+=")(pairType.reference_member("right"))
    val mutatedEqualsTen = pairType.reference_member("left").operator("equals")(registry.caster.cast("int", 10.0))
    assert(registry.caster.retrieve("byte", mutatedEqualsTen) == 1.0)
