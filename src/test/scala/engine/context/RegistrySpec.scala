package engine.context

import engine.context.ContextValue
import engine.context.operation.prefabs.ContextAddFactOperation
import engine.context.strategy.{ContextAtomicStrategy, ContextStrategyId, ContextStrategyKind}
import engine.context.registry.{ContextOperationRegistry, ContextStrategyRegistry}

class RegistrySpec extends munit.FunSuite:
  test("registers and retrieves operation"):
    val op = ContextAddFactOperation("add", "x", ContextValue.ContextStringValue("y"))
    val registry = ContextOperationRegistry.empty.register(op).toOption.get
    assertEquals(registry.get("add"), Some(op))

  test("rejects duplicate operation ID"):
    val op1 = ContextAddFactOperation("add", "x", ContextValue.ContextStringValue("a"))
    val op2 = ContextAddFactOperation("add", "x", ContextValue.ContextStringValue("b"))
    val registry = ContextOperationRegistry.empty.register(op1).toOption.get
    assert(registry.register(op2).isLeft)

  test("registers and retrieves ContextStrategy"):
    val ContextStrategy = ContextAtomicStrategy(ContextStrategyId("s"), "S", ContextStrategyKind.Meta, "op")
    val registry = ContextStrategyRegistry.empty.register(ContextStrategy).toOption.get
    assertEquals(registry.get(ContextStrategyId("s")), Some(ContextStrategy))
