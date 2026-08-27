class TypeTests extends munit.FunSuite:
  test("test type creation and base functional iteration, type and size natures."):

    val particle =
      Value(
        name = "Particle",
        shape = Vector(5),
        fields = Map(
          "id"       -> "long",
          "mass"     -> "double",
          "position" ->
            ValueTypeImpl(
              name = "Array",
              shape = Vector(3),
              fields = Map(
                "value" -> "int"
              )
            )
        )
      )

    val detail = particle.detail_self()
    assertEquals(detail.elementSize, 28L)
    assertEquals(detail.totalSize, 140L)
    assertEquals(detail.fields("id").offset, 0L)
    assertEquals(detail.fields("mass").offset, 8L)
    assertEquals(detail.fields("position").offset, 16L)
    assertEquals(detail.fields("position").length, 12L)

    particle.allocate()
    assertEquals(particle.memory.length, 140)

    // Accessor types.
    particle.push(Array.fill[Byte](140)(0))
    val particleMass = particle(2)("mass")
    assertEquals(particleMass.offset, 64L)
    assertEquals(particleMass.length, 8L)
    particleMass.write(Array.tabulate[Byte](8)(_.toByte))
    assertEquals(particleMass.read().toVector, Vector.tabulate[Byte](8)(_.toByte))

    val pIter = particle(0).iterator()
    val visited = pIter.toVector

    // Then p_iter will have the offset and length in the memory of the value stuff.
    assertEquals(visited.map(_.offset), Vector(0L, 8L, 16L))
    assertEquals(particle.iterate_dimension().size, 5)
    assertEquals(particle.iterate_value().size, 15)


// Then when building a type you can like create a base type, and just append shit to the map, and register base type sizes, and or reuse any base operations on the base types.
