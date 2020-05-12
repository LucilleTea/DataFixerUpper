// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.mojang.datafixers.types.templates;

import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixerUpper;
import com.mojang.datafixers.FamilyOptic;
import com.mojang.datafixers.FunctionType;
import com.mojang.datafixers.RewriteResult;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.Typed;
import com.mojang.datafixers.TypedOptic;
import com.mojang.datafixers.View;
import com.mojang.datafixers.functions.Functions;
import com.mojang.datafixers.kinds.App;
import com.mojang.datafixers.kinds.Applicative;
import com.mojang.datafixers.kinds.K1;
import com.mojang.datafixers.optics.Affine;
import com.mojang.datafixers.optics.Lens;
import com.mojang.datafixers.optics.Optic;
import com.mojang.datafixers.optics.Optics;
import com.mojang.datafixers.optics.Traversal;
import com.mojang.datafixers.optics.profunctors.AffineP;
import com.mojang.datafixers.optics.profunctors.Cartesian;
import com.mojang.datafixers.optics.profunctors.TraversalP;
import com.mojang.datafixers.types.DynamicOps;
import com.mojang.datafixers.types.Type;
import com.mojang.datafixers.types.families.RecursiveTypeFamily;
import com.mojang.datafixers.types.families.TypeFamily;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class TaggedChoice<K> implements TypeTemplate {
    private static final Logger LOGGER = LogManager.getLogger();

    private final String name;
    private final Type<K> keyType;
    private final Object2ObjectMap<K, TypeTemplate> templates;
    private final Map<Pair<TypeFamily, Integer>, Type<?>> types = Maps.newConcurrentMap();
    private final int size;

    private TaggedChoice(final String name, final Type<K> keyType, final Object2ObjectMap<K, TypeTemplate> templates) {
        this.name = name;
        this.keyType = keyType;
        this.templates = templates;
        size = templates.values().stream().mapToInt(TypeTemplate::size).max().orElse(0);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public TypeFamily apply(final TypeFamily family) {
        return index -> types.computeIfAbsent(Pair.of(family, index), key ->
                {
                    Object2ObjectMap<K, Type<?>> map = new Object2ObjectOpenHashMap<>();
                    for (Map.Entry<K, TypeTemplate> e : Object2ObjectMaps.fastIterable(templates)) {
                        if (map.put(e.getKey(), e.getValue().apply(key.getFirst()).apply(key.getSecond()))!=null) {
                            throw new IllegalStateException("Duplicate key");
                        }
                    }
                    return DSL.taggedChoiceType(name, keyType, map);
                }
        );
    }

    @Override
    public <A, B> FamilyOptic<A, B> applyO(final FamilyOptic<A, B> input, final Type<A> aType, final Type<B> bType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <A, B> Either<TypeTemplate, Type.FieldNotFoundException> findFieldOrType(final int index, @Nullable final String name, final Type<A> type, final Type<B> resultType) {
        return Either.right(new Type.FieldNotFoundException("Not implemented"));
    }

    @Override
    public IntFunction<RewriteResult<?, ?>> hmap(final TypeFamily family, final IntFunction<RewriteResult<?, ?>> function) {
        return index -> {
            RewriteResult<Pair<K, ?>, Pair<K, ?>> result = RewriteResult.nop((TaggedChoiceType<K>) apply(family).apply(index));
            for (final Map.Entry<K, TypeTemplate> entry : Object2ObjectMaps.fastIterable(templates)) {
                final RewriteResult<?, ?> elementResult = entry.getValue().hmap(family, function).apply(index);
                result = TaggedChoiceType.elementResult(entry.getKey(), (TaggedChoiceType<K>) result.view().newType(), elementResult).compose(result);
            }
            return result;
        };
    }

    @Override
    public String toString() {
        return "TaggedChoice[" + name + ", " + Joiner.on(", ").withKeyValueSeparator(" -> ").join(templates) + "]";
    }

    public static final class TaggedChoiceType<K> extends Type<Pair<K, ?>> {
        private final String name;
        private final Type<K> keyType;
        protected final Object2ObjectMap<K, Type<?>> types;
        private int hashCode;

        public TaggedChoiceType(final String name, final Type<K> keyType, final Object2ObjectMap<K, Type<?>> types) {
            this.name = name;
            this.keyType = keyType;
            this.types = types;
        }

        @Override
        public RewriteResult<Pair<K, ?>, ?> all(final TypeRewriteRule rule, final boolean recurse, final boolean checkIndex) {
            Object2ObjectMap<K, RewriteResult<?, ?>> map = new Object2ObjectOpenHashMap<>();
            for (Map.Entry<K, Type<?>> e : Object2ObjectMaps.fastIterable(types)) {
                Optional<? extends RewriteResult<?, ?>> k = rule.rewrite(e.getValue());

                if (k.isPresent() && !Functions.isId(k.get().view().function())) {
                    if (map.put(e.getKey(), k.get())!=null) {
                        throw new IllegalStateException("Duplicate key");
                    }
                }
            }
            if (map.isEmpty()) {
                return RewriteResult.nop(this);
            } else if (map.size() == 1) {
                final Map.Entry<K, ? extends RewriteResult<?, ?>> entry = ((Map<K, ? extends RewriteResult<?, ?>>) map).entrySet().iterator().next();
                return elementResult(entry.getKey(), this, entry.getValue());
            }
            final Object2ObjectMap<K, Type<?>> newTypes = new Object2ObjectOpenHashMap<>(types);
            final BitSet recData = new BitSet();
            for (final Map.Entry<K, ? extends RewriteResult<?, ?>> entry : Object2ObjectMaps.fastIterable(map)) {
                newTypes.put(entry.getKey(), entry.getValue().view().newType());
                recData.or(entry.getValue().recData());
            }
            return RewriteResult.create(View.create(this, DSL.taggedChoiceType(name, keyType, newTypes), Functions.fun("TaggedChoiceTypeRewriteResult " + map.size(), new RewriteFunc<>(map))), recData);
        }

        public static <K, FT, FR> RewriteResult<Pair<K, ?>, Pair<K, ?>> elementResult(final K key, final TaggedChoiceType<K> type, final RewriteResult<FT, FR> result) {
            return opticView(type, result, TypedOptic.tagged(type, key, result.view().type(), result.view().newType()));
        }

        @Override
        public Optional<RewriteResult<Pair<K, ?>, ?>> one(final TypeRewriteRule rule) {
            for (final Map.Entry<K, Type<?>> entry : Object2ObjectMaps.fastIterable(types)) {
                final Optional<? extends RewriteResult<?, ?>> elementResult = rule.rewrite(entry.getValue());
                if (elementResult.isPresent()) {
                    return Optional.of(elementResult(entry.getKey(), this, elementResult.get()));
                }
            }
            return Optional.empty();
        }

        @Override
        public Type<?> updateMu(final RecursiveTypeFamily newFamily) {
            Object2ObjectMap<K, Type<?>> map = new Object2ObjectOpenHashMap<>();
            for (Map.Entry<K, Type<?>> e : Object2ObjectMaps.fastIterable(types)) {
                if (map.put(e.getKey(), e.getValue().updateMu(newFamily))!=null) {
                    throw new IllegalStateException("Duplicate key");
                }
            }
            return DSL.taggedChoiceType(name, keyType, map);
        }

        @Override
        public TypeTemplate buildTemplate() {
            Object2ObjectMap<K, TypeTemplate> map = new Object2ObjectOpenHashMap<>();
            for (Map.Entry<K, Type<?>> e : Object2ObjectMaps.fastIterable(types)) {
                if (map.put(e.getKey(), e.getValue().template())!=null) {
                    throw new IllegalStateException("Duplicate key");
                }
            }
            return DSL.taggedChoice(name, keyType, map);
        }

        @Override
        public <T> Pair<T, Optional<Pair<K, ?>>> read(final DynamicOps<T> ops, final T input) {
            final Optional<Map<T, T>> values = ops.getMapValues(input);
            if (values.isPresent()) {
                final Map<T, T> map = values.get();
                final T nameObject = ops.createString(name);
                final T mapValue = map.get(nameObject);
                if (mapValue != null) {
                    final Optional<K> key = keyType.read(ops, mapValue).getSecond();
                    //noinspection OptionalIsPresent
                    final K keyValue = key.isPresent() ? key.get() : null;
                    final Type<?> type = keyValue != null ? types.get(keyValue) : null;
                    if (type == null) {
                        if (DataFixerUpper.ERRORS_ARE_FATAL) {
                            throw new IllegalArgumentException("Unsupported key: " + keyValue + " in " + this);
                        } else {
                            LOGGER.warn("Unsupported key: {} in {}", keyValue, this);
                            return Pair.of(input, Optional.empty());
                        }
                    }

                    return type.read(ops, input).mapSecond(vo -> vo.map(v -> Pair.of(keyValue, v)));
                }
            }
            return Pair.of(input, Optional.empty());
        }

        @Override
        public <T> T write(final DynamicOps<T> ops, final T rest, final Pair<K, ?> value) {
            final Type<?> type = types.get(value.getFirst());
            if (type == null) {
                // TODO: better error handling?
                // TODO: See todo in read method
                throw new IllegalArgumentException("Unsupported key: " + value.getFirst() + " in " + this);
            }
            return capWrite(ops, type, value.getFirst(), value.getSecond(), rest);
        }

        @SuppressWarnings("unchecked")
        private <T, A> T capWrite(final DynamicOps<T> ops, final Type<A> type, final K key, final Object value, final T rest) {
            return ops.mergeInto(type.write(ops, rest, (A) value), ops.createString(name), keyType.write(ops, ops.empty(), key));
        }

        @Override
        public Optional<Type<?>> findFieldTypeOpt(final String name) {
            return types.values().stream().map(t -> t.findFieldTypeOpt(name)).filter(Optional::isPresent).findFirst().flatMap(Function.identity());
        }

        @Override
        public Optional<Pair<K, ?>> point(final DynamicOps<?> ops) {
            return types.entrySet().stream().map(e -> e.getValue().point(ops).map(value -> Pair.of(e.getKey(), value))).filter(Optional::isPresent).findFirst().flatMap(Function.identity()).map(p -> p);
        }

        public Optional<Typed<Pair<K, ?>>> point(final DynamicOps<?> ops, final K key, final Object value) {
            if (!types.containsKey(key)) {
                return Optional.empty();
            }
            return Optional.of(new Typed<>(this, ops, Pair.of(key, value)));
        }

        @Override
        public <FT, FR> Either<TypedOptic<Pair<K, ?>, ?, FT, FR>, FieldNotFoundException> findTypeInChildren(final Type<FT> type, final Type<FR> resultType, final TypeMatcher<FT, FR> matcher, final boolean recurse) {
            final Map<K, ? extends TypedOptic<?, ?, FT, FR>> optics = types.entrySet().stream().map(
                e -> Pair.of(e.getKey(), e.getValue().findType(type, resultType, matcher, recurse))
            ).filter(
                e -> e.getSecond().left().isPresent()
            ).map(
                e -> e.mapSecond(o -> o.left().get())
            ).collect(Collectors.toMap(Pair::getFirst, Pair::getSecond));

            if (optics.isEmpty()) {
                return Either.right(new FieldNotFoundException("Not found in any choices"));
            } else if (optics.size() == 1) {
                final Map.Entry<K, ? extends TypedOptic<?, ?, FT, FR>> entry = optics.entrySet().iterator().next();
                return Either.left(cap(this, entry.getKey(), entry.getValue()));
            } else {
                final Set<TypeToken<? extends K1>> bounds = Sets.newHashSet();
                optics.values().forEach(o -> bounds.addAll(o.bounds()));

                final Optic<?, Pair<K, ?>, Pair<K, ?>, FT, FR> optic;
                final TypeToken<? extends K1> bound;

                // TODO: cache casts

                if (TypedOptic.instanceOf(bounds, Cartesian.Mu.TYPE_TOKEN) && optics.size() == types.size()) {
                    bound = Cartesian.Mu.TYPE_TOKEN;

                    optic = new Lens<Pair<K, ?>, Pair<K, ?>, FT, FR>() {
                        @Override
                        public FT view(final Pair<K, ?> s) {
                            final TypedOptic<?, ?, FT, FR> optic = optics.get(s.getFirst());
                            return capView(s, optic);
                        }

                        @SuppressWarnings("unchecked")
                        private <S, T> FT capView(final Pair<K, ?> s, final TypedOptic<S, T, FT, FR> optic) {
                            return Optics.toLens(optic.upCast(Cartesian.Mu.TYPE_TOKEN).orElseThrow(IllegalArgumentException::new)).view((S) s.getSecond());
                        }

                        @Override
                        public Pair<K, ?> update(final FR b, final Pair<K, ?> s) {
                            final TypedOptic<?, ?, FT, FR> optic = optics.get(s.getFirst());
                            return capUpdate(b, s, optic);
                        }

                        @SuppressWarnings("unchecked")
                        private <S, T> Pair<K, ?> capUpdate(final FR b, final Pair<K, ?> s, final TypedOptic<S, T, FT, FR> optic) {
                            return Pair.of(s.getFirst(), Optics.toLens(optic.upCast(Cartesian.Mu.TYPE_TOKEN).orElseThrow(IllegalArgumentException::new)).update(b, (S) s.getSecond()));
                        }
                    };
                } else if (TypedOptic.instanceOf(bounds, AffineP.Mu.TYPE_TOKEN)) {
                    bound = AffineP.Mu.TYPE_TOKEN;

                    optic = new Affine<Pair<K, ?>, Pair<K, ?>, FT, FR>() {
                        @Override
                        public Either<Pair<K, ?>, FT> preview(final Pair<K, ?> s) {
                            if (!optics.containsKey(s.getFirst())) {
                                return Either.left(s);
                            }
                            final TypedOptic<?, ?, FT, FR> optic = optics.get(s.getFirst());
                            return capPreview(s, optic);
                        }

                        @SuppressWarnings("unchecked")
                        private <S, T> Either<Pair<K, ?>, FT> capPreview(final Pair<K, ?> s, final TypedOptic<S, T, FT, FR> optic) {
                            return Optics.toAffine(optic.upCast(AffineP.Mu.TYPE_TOKEN).orElseThrow(IllegalArgumentException::new)).preview((S) s.getSecond()).mapLeft(t -> (Pair<K, ?>) Pair.of(s.getFirst(), t));
                        }

                        @Override
                        public Pair<K, ?> set(final FR b, final Pair<K, ?> s) {
                            if (!optics.containsKey(s.getFirst())) {
                                return s;
                            }
                            final TypedOptic<?, ?, FT, FR> optic = optics.get(s.getFirst());
                            return capSet(b, s, optic);
                        }

                        @SuppressWarnings("unchecked")
                        private <S, T> Pair<K, ?> capSet(final FR b, final Pair<K, ?> s, final TypedOptic<S, T, FT, FR> optic) {
                            return Pair.of(s.getFirst(), Optics.toAffine(optic.upCast(AffineP.Mu.TYPE_TOKEN).orElseThrow(IllegalArgumentException::new)).set(b, (S) s.getSecond()));
                        }
                    };
                } else if (TypedOptic.instanceOf(bounds, TraversalP.Mu.TYPE_TOKEN)) {
                    bound = TraversalP.Mu.TYPE_TOKEN;

                    optic = new Traversal<Pair<K, ?>, Pair<K, ?>, FT, FR>() {
                        @Override
                        public <F extends K1> FunctionType<Pair<K, ?>, App<F, Pair<K, ?>>> wander(final Applicative<F, ?> applicative, final FunctionType<FT, App<F, FR>> input) {
                            return pair -> {
                                if (!optics.containsKey(pair.getFirst())) {
                                    return applicative.point(pair);
                                }
                                final TypedOptic<?, ?, FT, FR> optic = optics.get(pair.getFirst());
                                return capTraversal(applicative, input, pair, optic);
                            };
                        }

                        @SuppressWarnings("unchecked")
                        private <S, T, F extends K1> App<F, Pair<K, ?>> capTraversal(final Applicative<F, ?> applicative, final FunctionType<FT, App<F, FR>> input, final Pair<K, ?> pair, final TypedOptic<S, T, FT, FR> optic) {
                            final Traversal<S, T, FT, FR> traversal = Optics.toTraversal(optic.upCast(TraversalP.Mu.TYPE_TOKEN).orElseThrow(IllegalArgumentException::new));
                            return applicative.ap(value -> Pair.of(pair.getFirst(), value), traversal.wander(applicative, input).apply((S) pair.getSecond()));
                        }
                    };
                } else {
                    throw new IllegalStateException("Could not merge TaggedChoiceType optics, unknown bound: " + Arrays.toString(bounds.toArray()));
                }

                Object2ObjectMap<K, Type<?>> map = new Object2ObjectOpenHashMap<>();
                for (Map.Entry<K, Type<?>> e : Object2ObjectMaps.fastIterable(types)) {
                    if (map.put(e.getKey(), optics.containsKey(e.getKey()) ? optics.get(e.getKey()).tType():e.getValue())!=null) {
                        throw new IllegalStateException("Duplicate key");
                    }
                }

                return Either.left(new TypedOptic<>(
                    bound,
                    this,
                    DSL.taggedChoiceType(name, keyType, map),
                    type,
                    resultType,
                    optic
                ));
            }
        }

        private <S, T, FT, FR> TypedOptic<Pair<K, ?>, Pair<K, ?>, FT, FR> cap(final TaggedChoiceType<K> choiceType, final K key, final TypedOptic<S, T, FT, FR> optic) {
            return TypedOptic.tagged(choiceType, key, optic.sType(), optic.tType()).compose(optic);
        }

        @Override
        public Optional<TaggedChoiceType<?>> findChoiceType(final String name, final int index) {
            if (Objects.equals(name, this.name)) {
                return Optional.of(this);
            }
            return Optional.empty();
        }

        @Override
        public Optional<Type<?>> findCheckedType(final int index) {
            return types.values().stream().map(type -> type.findCheckedType(index)).filter(Optional::isPresent).findFirst().flatMap(Function.identity());
        }

        @Override
        public boolean equals(final Object obj, final boolean ignoreRecursionPoints, final boolean checkIndex) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof TaggedChoice.TaggedChoiceType)) {
                return false;
            }
            final TaggedChoiceType<?> other = (TaggedChoiceType<?>) obj;
            if (!Objects.equals(name, other.name)) {
                return false;
            }
            if (!(keyType.equals(other.keyType, ignoreRecursionPoints, checkIndex))) {
                return false;
            }
            if (types.size() != other.types.size()) {
                return false;
            }
            for (final Map.Entry<K, Type<?>> entry : Object2ObjectMaps.fastIterable(types)) {
                if (!entry.getValue().equals(other.types.get(entry.getKey()), ignoreRecursionPoints, checkIndex)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            if (hashCode == 0) {
                hashCode = Objects.hash(name, keyType, types);
            }
            return hashCode;
        }

        @Override
        public String toString() {
            return "TaggedChoiceType[" + name + ", " + Joiner.on(", \n").withKeyValueSeparator(" -> ").join(types) + "]\n";
        }

        public String getName() {
            return name;
        }

        public Type<K> getKeyType() {
            return keyType;
        }

        public boolean hasType(final K key) {
            return types.containsKey(key);
        }

        public Map<K, Type<?>> types() {
            return types;
        }

        private static final class RewriteFunc<K> implements Function<DynamicOps<?>, Function<Pair<K, ?>, Pair<K, ?>>> {
            private final Map<K, ? extends RewriteResult<?, ?>> results;

            public RewriteFunc(final Map<K, ? extends RewriteResult<?, ?>> results) {
                this.results = results;
            }

            @Override
            public FunctionType<Pair<K, ?>, Pair<K, ?>> apply(final DynamicOps<?> ops) {
                return input -> {
                    final RewriteResult<?, ?> result = results.get(input.getFirst());
                    if (result == null) {
                        return input;
                    }
                    return capRuleApply(ops, input, result);
                };
            }

            @SuppressWarnings("unchecked")
            private <A, B> Pair<K, B> capRuleApply(final DynamicOps<?> ops, final Pair<K, ?> input, final RewriteResult<A, B> result) {
                return input.mapSecond(v -> result.view().function().evalCached().apply(ops).apply((A) v));
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) {
                    return true;
                }
                if (o == null || getClass() != o.getClass()) {
                    return false;
                }
                final RewriteFunc<?> that = (RewriteFunc<?>) o;
                return Objects.equals(results, that.results);
            }

            @Override
            public int hashCode() {
                return Objects.hash(results);
            }
        }
    }

    public static class CreateInfo<K> implements Supplier<TaggedChoice<?>> {
        private final String name;
        private final Type<K> keyType;
        private final Object2ObjectMap<K, TypeTemplate> templates;

        public CreateInfo(String name, Type<K> keyType, Object2ObjectMap<K, TypeTemplate> templates) {
            this.name = name;
            this.keyType = keyType;
            this.templates = templates;
        }

        @Override
        public TaggedChoice<K> get() {
            return new TaggedChoice<>(name, keyType, templates);
        }

        @Override
        public boolean equals(Object o) {
            if (this==o) return true;
            if (o==null || getClass()!=o.getClass()) return false;
            CreateInfo<?> that = (CreateInfo<?>) o;
            return name.equals(that.name) &&
                    keyType.equals(that.keyType) &&
                    templates.equals(that.templates);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, keyType, templates);
        }
    }
}
