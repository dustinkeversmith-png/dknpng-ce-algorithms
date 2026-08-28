// I added test_base_ops right in here for testing the base operations between all the basic types using Value that has type of any base type.

class BaseOperatorTests extends munit.FunSuite:
  test("register base overloads with FunctionalId and run them on fields of a new type"):
    val baseTypes = new BaseTypes()
    val registry = baseTypes.registerAll()

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
    pairType.registry = registry
    pairType.index_fields()
    pairType.allocate()

    pairType("left").operators("=")(4)
    pairType("right").operators("=")(6)

    val addedFields = integerOperators.operator(
      "add",
      Vector(pairType("left"), pairType("right"))
    )

    assert(addedFields.operators("equals")(10).truth())

    pairType("left").operators("+=")(pairType("right"))
    assert(pairType("left").operators("equals")(10).truth())
