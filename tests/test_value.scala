
class TypeTests extends munit.FunSuite:
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

