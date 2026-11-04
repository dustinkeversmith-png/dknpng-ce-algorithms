#include "combinatorial/value.hpp"

#include <gtest/gtest.h>

#include <array>
#include <bit>
#include <cmath>
#include <limits>

using namespace combinatorial::value;

namespace {

struct ValueTest : ::testing::Test {
    TypeRegistry reg;
    TypePtr point = reg.define_struct("Point", {{"x", "f64"}, {"y", "f64"}});
    TypePtr particle = reg.define_struct("Particle", {{"id", "u32"}, {"pos", "Point"}, {"hist", "Point[3]"}, {"tag", "u8"}});
};

}  // namespace

// ---------------------------------------------------------------- layout ---

TEST_F(ValueTest, PrimitiveSizes) {
    EXPECT_EQ(reg.primitive_size("u8"), 1u);
    EXPECT_EQ(reg.primitive_size("i16"), 2u);
    EXPECT_EQ(reg.primitive_size("f32"), 4u);
    EXPECT_EQ(reg.primitive_size("u64"), 8u);
    EXPECT_EQ(reg.primitive_size("double"), 8u);
    EXPECT_EQ(reg.primitive_size("bool"), 1u);
    EXPECT_THROW(reg.primitive_size("Point"), std::invalid_argument);
}

TEST_F(ValueTest, RecursiveStructMeasurementIsPacked) {
    EXPECT_EQ(point->byte_size(), 16u);
    // u32 + Point + Point[3] + u8 = 4 + 16 + 48 + 1
    EXPECT_EQ(particle->byte_size(), 69u);
    EXPECT_EQ(particle->field("id")->offset, 0u);
    EXPECT_EQ(particle->field("pos")->offset, 4u);
    EXPECT_EQ(particle->field("hist")->offset, 20u);
    EXPECT_EQ(particle->field("tag")->offset, 68u);
    EXPECT_EQ(reg.measure("Particle[2,5]"), 69u * 10u);
}

TEST_F(ValueTest, StridesAreRowMajorBytes) {
    TypePtr t = reg.get("f32[2,3,4]");
    ASSERT_EQ(t->rank(), 3u);
    EXPECT_EQ(t->strides(), (std::vector<std::size_t>{48, 16, 4}));
    EXPECT_EQ(t->element_count(), 24u);
    EXPECT_EQ(t->byte_size(), 96u);
    // equivalent bracket syntaxes
    EXPECT_TRUE(reg.get("f32[2][3][4]")->equals(*t));
    EXPECT_TRUE(reg.get(" f32 [2, 3,4] ")->equals(*t));
}

TEST_F(ValueTest, AliasedShapedTypesPrependOuterDims) {
    reg.define_alias("vec3", reg.get("f64[3]"));
    TypePtr t = reg.get("vec3[4]");
    EXPECT_EQ(t->shape(), (std::vector<std::size_t>{4, 3}));
}

TEST_F(ValueTest, SuffixTypesAreCachedAndShared) {
    TypePtr t = reg.get("u16[5,6,7]");
    EXPECT_EQ(t->suffix(1)->shape(), (std::vector<std::size_t>{6, 7}));
    EXPECT_EQ(t->suffix(1).get(), t->suffix(1).get());
    EXPECT_EQ(t->suffix(1)->suffix(1).get(), t->suffix(2).get());
    EXPECT_EQ(t->element().get(), t->suffix(3).get());
    EXPECT_TRUE(t->element()->is_scalar());
    EXPECT_THROW(t->suffix(4), std::out_of_range);
}

TEST_F(ValueTest, StructuralEqualityIgnoresDisplayName) {
    auto a = ValueType::structure("P", {{"x", reg.get("f64")}});
    auto b = ValueType::structure("P", {{"x", reg.get("double")}});
    auto c = ValueType::structure("P", {{"y", reg.get("f64")}});
    auto d = ValueType::structure("Q", {{"x", reg.get("f64")}});
    EXPECT_TRUE(a->equals(*b));
    EXPECT_FALSE(a->equals(*c));  // field names differ
    EXPECT_TRUE(a->layout_equals(*c));
    EXPECT_FALSE(a->equals(*d));  // canonical type differs
    EXPECT_TRUE(a->layout_equals(*d));
}

TEST_F(ValueTest, SignatureIsReadable) {
    EXPECT_EQ(reg.get("Point[2,2]")->signature(), "Point{x:f64,y:f64}[2,2]");
}

TEST_F(ValueTest, RegistryRejectsBadInput) {
    EXPECT_THROW(reg.get("nope"), std::invalid_argument);
    EXPECT_THROW(reg.get("f64[3"), std::invalid_argument);
    EXPECT_THROW(reg.get("f64[a]"), std::invalid_argument);
    EXPECT_THROW(reg.get("f64[2]x"), std::invalid_argument);
    EXPECT_THROW(reg.define_struct("Point", {{"x", "f64"}}), std::invalid_argument);
    EXPECT_THROW(reg.define_struct("Dup", {{"x", "f64"}, {"x", "u8"}}), std::invalid_argument);
    EXPECT_THROW(ValueType::primitive("bad", Prim::Opaque), std::invalid_argument);
    EXPECT_THROW(reg.get("u64[4294967296,4294967296]"), std::length_error);
}

TEST_F(ValueTest, OpaquePrimitive) {
    TypePtr digest = reg.define_primitive("sha256", 32);
    EXPECT_EQ(digest->byte_size(), 32u);
    EXPECT_EQ(digest->t(), "sha256");
    Value v(digest);
    EXPECT_THROW(v.as<double>(), std::invalid_argument);
    EXPECT_FALSE(is_numeric_layout(*digest));
}

// ------------------------------------------------------------- strided ---

TEST_F(ValueTest, StridedIndexingMatchesRowMajorFormula) {
    Value t(reg.get("i32[3,4,5]"), "t");
    for (std::size_t i = 0; i < 3; ++i)
        for (std::size_t j = 0; j < 4; ++j)
            for (std::size_t k = 0; k < 5; ++k) t.at({i, j, k}).set<std::int32_t>(static_cast<std::int32_t>(100 * i + 10 * j + k));
    for (std::size_t flat = 0; flat < 60; ++flat) {
        std::size_t i = flat / 20, j = (flat / 5) % 4, k = flat % 5;
        EXPECT_EQ(t.flat(flat).get<std::int32_t>(), static_cast<std::int32_t>(100 * i + 10 * j + k));
        FieldIndex fi = t.index_dimension({i, j, k});
        EXPECT_EQ(fi.offset, flat * 4);
        EXPECT_EQ(fi.length, 4u);
    }
}

TEST_F(ValueTest, PartialIndexingYieldsSubArrayViews) {
    Value t(reg.get("u8[4,3,2]"));
    Value plane = t[2];
    EXPECT_EQ(plane.type()->shape(), (std::vector<std::size_t>{3, 2}));
    EXPECT_EQ(plane.offset(), 2u * 6u);
    Value row = plane[1];
    EXPECT_EQ(row.type()->shape(), (std::vector<std::size_t>{2}));
    row[1].set<std::uint8_t>(0xAB);
    EXPECT_EQ(t.at({2, 1, 1}).get<std::uint8_t>(), 0xAB);
    EXPECT_EQ(std::to_integer<int>((*t.memory())[2 * 6 + 1 * 2 + 1]), 0xAB);
    FieldIndex fi = t.index_dimension({1});
    EXPECT_EQ(fi.length, 6u);
}

TEST_F(ValueTest, IndexingBoundsAreChecked) {
    Value t(reg.get("f64[2,3]"));
    EXPECT_THROW(t.at({2, 0}), std::out_of_range);
    EXPECT_THROW(t.at({0, 3}), std::out_of_range);
    EXPECT_THROW(t.at({0, 0, 0}), std::out_of_range);
    EXPECT_THROW(t.flat(6), std::out_of_range);
    EXPECT_NO_THROW(t.at({1, 2}));
}

TEST_F(ValueTest, ArraysOfStructsStrideByElementSize) {
    Value ps(reg.get("Particle[4]"));
    for (std::size_t i = 0; i < 4; ++i) {
        ps[i].reference("pos.y").set<double>(static_cast<double>(i) + 0.5);
        ps[i].reference("hist[2].x").set<double>(-static_cast<double>(i));
    }
    for (std::size_t i = 0; i < 4; ++i) {
        EXPECT_EQ(ps.reference("[" + std::to_string(i) + "].pos.y").offset(), i * 69 + 4 + 8);
        EXPECT_DOUBLE_EQ(ps.reference("[" + std::to_string(i) + "].pos.y").get<double>(), static_cast<double>(i) + 0.5);
        EXPECT_DOUBLE_EQ(ps[i].reference_member("hist")[2].reference_member("x").get<double>(), -static_cast<double>(i));
    }
}

// --------------------------------------------------------- zero-copy ---

TEST_F(ValueTest, ViewsShareMemoryWithRoot) {
    Value p(particle, "p");
    Value pos = p.reference_member("pos");
    Value x = pos.reference_member("x");
    EXPECT_EQ(x.memory().get(), p.memory().get());
    EXPECT_TRUE(x.aliases(p));
    EXPECT_TRUE(pos.aliases(x));
    x.set<double>(3.25);
    EXPECT_DOUBLE_EQ(p.reference("pos.x").get<double>(), 3.25);
    // a view keeps the buffer alive after the root goes away
    Value survivor = Value(particle).reference("hist[1].y");
    survivor.set<double>(7.0);
    EXPECT_DOUBLE_EQ(survivor.get<double>(), 7.0);
}

TEST_F(ValueTest, DisjointViewsDoNotAlias) {
    Value p(particle);
    EXPECT_FALSE(p.reference("pos").aliases(p.reference("hist")));
    EXPECT_FALSE(p.reference("hist[0]").aliases(p.reference("hist[1]")));
    EXPECT_FALSE(p.aliases(Value(particle)));
}

TEST_F(ValueTest, CloneIsDeep) {
    Value p(particle);
    p.reference("id").set<std::uint32_t>(42);
    Value q = p.clone();
    EXPECT_EQ(p, q);
    q.reference("id").set<std::uint32_t>(43);
    EXPECT_EQ(p.reference("id").get<std::uint32_t>(), 42u);
    EXPECT_NE(p, q);
    EXPECT_FALSE(p.aliases(q));
}

TEST_F(ValueTest, CopyFromRequiresLayoutMatch) {
    Value a(reg.get("Point[3]")), b(reg.get("Point[3]"));
    a.reference("[1].y").set<double>(9.0);
    b.copy_from(a);
    EXPECT_DOUBLE_EQ(b.reference("[1].y").get<double>(), 9.0);
    // copy a sub-view into another sub-view
    Value p(particle);
    p.reference("hist").copy_from(a);
    EXPECT_DOUBLE_EQ(p.reference("hist[1].y").get<double>(), 9.0);
    EXPECT_THROW(p.reference("pos").copy_from(a), std::invalid_argument);
}

TEST_F(ValueTest, ViewConstructorValidatesBounds) {
    auto mem = std::make_shared<Value::Memory>(16);
    EXPECT_NO_THROW(Value(reg.get("f64"), mem, 8));
    EXPECT_THROW(Value(reg.get("f64"), mem, 9), std::out_of_range);
    EXPECT_THROW(Value(reg.get("f64[3]"), mem, 0), std::out_of_range);
    EXPECT_THROW(Value(reg.get("u8"), mem, 17), std::out_of_range);
}

TEST_F(ValueTest, InsertAndReadRawBytes) {
    Value v(reg.get("u8[8]"));
    const std::array<std::byte, 3> src{std::byte{1}, std::byte{2}, std::byte{3}};
    v.insert(src, 4);
    std::array<std::byte, 4> dst{};
    v.read(dst, 3);
    EXPECT_EQ(std::to_integer<int>(dst[0]), 0);
    EXPECT_EQ(std::to_integer<int>(dst[1]), 1);
    EXPECT_EQ(std::to_integer<int>(dst[3]), 3);
    EXPECT_THROW(v.insert(src, 6), std::out_of_range);
    EXPECT_THROW(v.read(dst, 5), std::out_of_range);
    // reading through a slice is relative to the slice
    Value tail = Value(reg.get("u8[4]"), v.memory(), 4);
    EXPECT_EQ(tail[0].get<std::uint8_t>(), 1);
}

// ------------------------------------------------------------- paths ---

TEST_F(ValueTest, PathGrammar) {
    Value grid(reg.get("Particle[2,3]"));
    grid.reference("[1,2].hist[0].x").set<double>(5.0);
    EXPECT_DOUBLE_EQ(grid.reference("[1][2].hist[0].x").get<double>(), 5.0);
    EXPECT_DOUBLE_EQ(grid.at({1, 2}).reference("hist[0].x").get<double>(), 5.0);
    EXPECT_EQ(grid.reference("").offset(), 0u);
    EXPECT_EQ(grid.locate("[1]").length, 3u * 69u);
}

TEST_F(ValueTest, PathErrors) {
    Value p(particle);
    EXPECT_THROW(p.reference("nope"), std::invalid_argument);
    EXPECT_THROW(p.reference("pos."), std::invalid_argument);
    EXPECT_THROW(p.reference("pos..x"), std::invalid_argument);
    EXPECT_THROW(p.reference("hist[3]"), std::out_of_range);
    EXPECT_THROW(p.reference("hist[0,0]"), std::invalid_argument);
    EXPECT_THROW(p.reference("hist.x"), std::invalid_argument);
    EXPECT_THROW(p.reference("hist[x]"), std::invalid_argument);
    EXPECT_THROW(p.reference("hist[1"), std::invalid_argument);
    EXPECT_THROW(p.reference("pos#"), std::invalid_argument);
    EXPECT_THROW(p.reference_member("hist").reference_member("x"), std::invalid_argument);
}

// ----------------------------------------------------- register casting ---

TEST_F(ValueTest, BitwiseRegisterCasting) {
    Value r(reg.get("u64"));
    r.set<double>(1.0);
    EXPECT_EQ(r.get<std::uint64_t>(), std::bit_cast<std::uint64_t>(1.0));
    r.set<std::int64_t>(-1);
    EXPECT_EQ(r.get<std::uint64_t>(), std::numeric_limits<std::uint64_t>::max());
    Value s(reg.get("i32"));
    s.set<float>(-0.0f);
    EXPECT_EQ(s.get<std::uint32_t>(), 0x80000000u);
    EXPECT_THROW(s.get<double>(), std::invalid_argument);   // size mismatch
    EXPECT_THROW(s.set<std::int16_t>(1), std::invalid_argument);
    Value arr(reg.get("u32[2]"));
    EXPECT_THROW(arr.get<std::uint32_t>(), std::invalid_argument);  // not scalar
}

TEST_F(ValueTest, EndiannessOfSubRegisterViews) {
    // A u32 register viewed through a u8[4] overlay exposes host byte order.
    Value r(reg.get("u32"));
    r.set<std::uint32_t>(0x11223344u);
    Value bytes(reg.get("u8[4]"), r.memory(), r.offset());
    if constexpr (std::endian::native == std::endian::little) {
        EXPECT_EQ(bytes[0].get<std::uint8_t>(), 0x44);
        EXPECT_EQ(bytes[3].get<std::uint8_t>(), 0x11);
    } else {
        EXPECT_EQ(bytes[0].get<std::uint8_t>(), 0x11);
    }
}

TEST_F(ValueTest, NumericCastsSaturate) {
    Value u8(reg.get("u8")), i8(reg.get("i8")), u64(reg.get("u64")), i64(reg.get("i64")), b(reg.get("bool"));
    u8.assign(300.0);
    EXPECT_EQ(u8.get<std::uint8_t>(), 255);
    u8.assign(-5.0);
    EXPECT_EQ(u8.get<std::uint8_t>(), 0);
    i8.assign(-1000.0);
    EXPECT_EQ(i8.get<std::int8_t>(), -128);
    i8.assign(std::nan(""));
    EXPECT_EQ(i8.get<std::int8_t>(), 0);
    u64.assign(1e30);
    EXPECT_EQ(u64.get<std::uint64_t>(), std::numeric_limits<std::uint64_t>::max());
    i64.assign(-1e30);
    EXPECT_EQ(i64.get<std::int64_t>(), std::numeric_limits<std::int64_t>::min());
    i64.set<std::int64_t>(-7);
    EXPECT_DOUBLE_EQ(i64.as<double>(), -7.0);
    EXPECT_EQ(i64.as<std::int32_t>(), -7);
    b.assign(2.0);
    EXPECT_TRUE(b.get<bool>());
}

TEST_F(ValueTest, BoolReadsNormaliseArbitraryBytes) {
    Value b(reg.get("bool"));
    const std::array<std::byte, 1> raw{std::byte{0x7F}};
    b.insert(raw);
    EXPECT_TRUE(b.get<bool>());
    EXPECT_EQ(b.as<int>(), 1);
}

TEST_F(ValueTest, VectorRoundTrip) {
    Value v(reg.get("i16[2,2]"));
    const std::vector<int> src{1, -2, 3, -4};
    v.from_vector<int>(src);
    EXPECT_EQ(v.to_vector<int>(), src);
    EXPECT_EQ(v.at({1, 0}).get<std::int16_t>(), 3);
    EXPECT_THROW(v.from_vector<int>(std::vector<int>{1}), std::invalid_argument);
}

// ----------------------------------------------------------- operators ---

TEST_F(ValueTest, BuiltinElementwiseOperators) {
    Value a(reg.get("i32[3]")), b(reg.get("i32[3]"));
    a.from_vector<int>(std::vector<int>{1, 2, 3});
    b.from_vector<int>(std::vector<int>{10, 20, 30});
    EXPECT_EQ(reg.apply("add", {a, b}).to_vector<int>(), (std::vector<int>{11, 22, 33}));
    EXPECT_EQ(reg.apply("sub", {a, b}).to_vector<int>(), (std::vector<int>{-9, -18, -27}));
    EXPECT_EQ(reg.apply("mul", {a, b}).to_vector<int>(), (std::vector<int>{10, 40, 90}));
    EXPECT_EQ(reg.apply("div", {b, a}).to_vector<int>(), (std::vector<int>{10, 10, 10}));
    EXPECT_EQ(reg.apply("max", {a, b}).to_vector<int>(), (std::vector<int>{10, 20, 30}));
    EXPECT_EQ(reg.apply("neg", {a}).to_vector<int>(), (std::vector<int>{-1, -2, -3}));
    EXPECT_DOUBLE_EQ(reg.apply("sum", {a}).get<double>(), 6.0);
    EXPECT_DOUBLE_EQ(reg.apply("dot", {a, b}).get<double>(), 140.0);
    EXPECT_EQ(reg.apply("lt", {a, b}).type()->prim(), Prim::Bool);
    EXPECT_EQ(reg.apply("lt", {a, b}).to_vector<int>(), (std::vector<int>{1, 1, 1}));
}

TEST_F(ValueTest, OperatorBroadcastAndErrors) {
    Value a(reg.get("f64[2,2]")), s(reg.get("f64"));
    a.from_vector<double>(std::vector<double>{1, 2, 3, 4});
    s.set<double>(0.5);
    Value r = reg.apply("mul", {a, s});
    EXPECT_EQ(r.type()->shape(), (std::vector<std::size_t>{2, 2}));
    EXPECT_DOUBLE_EQ(r.at({1, 1}).get<double>(), 2.0);
    Value z(reg.get("i32[2]")), y(reg.get("i32[2]"));
    y.from_vector<int>(std::vector<int>{1, 0});
    EXPECT_THROW(reg.apply("div", {z, y}), std::domain_error);
    EXPECT_THROW(reg.apply("add", {a, Value(reg.get("f64[3]"))}), std::invalid_argument);
    EXPECT_THROW(reg.apply("add", {Value(point), Value(point)}), std::invalid_argument);
    EXPECT_THROW(reg.apply("frobnicate", {a}), std::invalid_argument);
}

TEST_F(ValueTest, SignedOverflowWrapsWithoutUB) {
    Value a(reg.get("i32")), b(reg.get("i32"));
    a.set<std::int32_t>(std::numeric_limits<std::int32_t>::max());
    b.set<std::int32_t>(1);
    EXPECT_EQ(reg.apply("add", {a, b}).get<std::int32_t>(), std::numeric_limits<std::int32_t>::min());
    a.set<std::int32_t>(std::numeric_limits<std::int32_t>::min());
    b.set<std::int32_t>(-1);
    EXPECT_THROW(reg.apply("div", {a, b}), std::domain_error);
}

TEST_F(ValueTest, UserDefinedOperatorDispatch) {
    reg.define_operator("norm2", "Point", [&](std::span<const Value> args) {
        Value out(reg.get("f64"));
        const double x = args[0].reference("x").get<double>(), y = args[0].reference("y").get<double>();
        out.set<double>(x * x + y * y);
        return out;
    });
    Value p(point);
    p.reference("x").set<double>(3);
    p.reference("y").set<double>(4);
    EXPECT_TRUE(reg.has_operator("norm2", "Point"));
    EXPECT_DOUBLE_EQ(reg.apply("norm2", {p}).get<double>(), 25.0);
    // explicit type-specific operators override the numeric wildcard
    reg.define_operator("add", "f64", [&](std::span<const Value>) {
        Value out(reg.get("f64"));
        out.set<double>(-1);
        return out;
    });
    EXPECT_DOUBLE_EQ(reg.apply("add", {Value(reg.get("f64")), Value(reg.get("f64"))}).get<double>(), -1.0);
}

// ------------------------------------------------------- leaves / print ---

TEST_F(ValueTest, FlattenEnumeratesLeavesInMemoryOrder) {
    auto leaves = flatten(*particle);
    ASSERT_EQ(leaves.size(), 1u + 2u + 6u + 1u);
    EXPECT_EQ(leaves[0].path, "id");
    EXPECT_EQ(leaves[1].path, "pos.x");
    EXPECT_EQ(leaves[3].path, "hist[0].x");
    EXPECT_EQ(leaves[8].path, "hist[2].y");
    EXPECT_EQ(leaves[9].path, "tag");
    Value p(particle);
    for (const Leaf& l : leaves) EXPECT_EQ(p.locate(l.path).offset, l.offset) << l.path;
    EXPECT_EQ(leaf_count(*reg.get("Particle[3,2]")), 60u);
    EXPECT_EQ(flatten(*reg.get("u8[2,2]"))[3].path, "[1,1]");
}

TEST_F(ValueTest, ToStringAndHash) {
    Value v(reg.get("i32[2,2]"));
    v.from_vector<int>(std::vector<int>{1, 2, 3, 4});
    EXPECT_EQ(v.to_string(), "[[1, 2], [3, 4]]");
    Value p(point);
    p.reference("x").set<double>(1.5);
    EXPECT_EQ(p.to_string(), "{x: 1.5, y: 0}");
    Value q = p.clone();
    EXPECT_EQ(p.hash(), q.hash());
    q.reference("y").set<double>(1);
    EXPECT_NE(p.hash(), q.hash());
    EXPECT_EQ(Value(reg.get("u8[0]")).to_string(), "[]");
}

TEST_F(ValueTest, ZeroExtentArrays) {
    Value v(reg.get("f64[0,4]"));
    EXPECT_EQ(v.size(), 0u);
    EXPECT_THROW(v.at({0}), std::out_of_range);
    Value w = v.clone();
    EXPECT_EQ(v, w);
}
