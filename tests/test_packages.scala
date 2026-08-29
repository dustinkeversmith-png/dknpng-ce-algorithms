import value.*
import problem.*

class PackageImportTests extends munit.FunSuite:
  test("value and problem wildcard imports expose the public project types"):
    val registry = new TypeRegistry()
    val valueType = new ValueType("Example", "int")
    val predicate = Predicate("always", (_: Value) => true)
    val invariant = Invariant("always", predicate)

    assert(registry.sizes.isEmpty)
    assert(valueType.t == "int")
    assert(invariant.name == "always")
