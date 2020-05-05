// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.mojang.serialization;

import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public abstract class MapCodec<A> extends MapDecoder.Implementation<A> implements MapEncoder<A>, Codec<A> {
    public final <O> RecordCodecBuilder<O, A> forGetter(final Function<O, A> getter) {
        return RecordCodecBuilder.of(getter, this);
    }

    public static <A> MapCodec<A> of(final MapEncoder<A> encoder, final MapDecoder<A> decoder) {
        return of(encoder, decoder, "MapCodec[" + encoder + " " + decoder + "]");
    }

    public static <A> MapCodec<A> of(final MapEncoder<A> encoder, final MapDecoder<A> decoder, final String name) {
        return new MapCodec<A>() {
            @Override
            public <T> Stream<T> keys(final DynamicOps<T> ops) {
                return Stream.concat(encoder.keys(ops), decoder.keys(ops));
            }

            @Override
            public <T> DataResult<A> decode(final DynamicOps<T> ops, final MapLike<T> input) {
                return decoder.decode(ops, input);
            }

            @Override
            public <T> RecordBuilder<T> encode(final A input, final DynamicOps<T> ops, final RecordBuilder<T> prefix) {
                return encoder.encode(input, ops, prefix);
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    @Override
    public MapCodec<A> withLifecycle(final Lifecycle lifecycle) {
        final MapCodec<A> self = this;

        return new MapCodec<A>() {
            @Override
            public <T> Stream<T> keys(final DynamicOps<T> ops) {
                return self.keys(ops);
            }

            @Override
            public <T> DataResult<A> decode(final DynamicOps<T> ops, final MapLike<T> input) {
                return self.decode(ops, input).setLifecycle(lifecycle);
            }

            @Override
            public <T> RecordBuilder<T> encode(final A input, final DynamicOps<T> ops, final RecordBuilder<T> prefix) {
                return self.encode(input, ops, prefix).setLifecycle(lifecycle);
            }

            @Override
            public String toString() {
                return self.toString();
            }
        };
    }

    @Override
    public MapCodec<A> stable() {
        return withLifecycle(Lifecycle.stable());
    }

    @Override
    public MapCodec<A> deprecated(final int since) {
        return withLifecycle(Lifecycle.deprecated(since));
    }

    public <S> MapCodec<S> xmap(final Function<? super A, ? extends S> to, final Function<? super S, ? extends A> from) {
        return MapCodec.of(comap(from), map(to), toString() + "[comapped]");
    }

    public <E> MapCodec<A> dependent(final MapCodec<E> initialInstance, final Function<A, Pair<E, MapCodec<E>>> splitter, final BiFunction<A, E, A> combiner) {
        return new Dependent<A, E>(this, initialInstance, splitter, combiner);
    }

    private static class Dependent<O, E> extends MapCodec<O> {
        private final MapCodec<E> initialInstance;
        private final Function<O, Pair<E, MapCodec<E>>> splitter;
        private final MapCodec<O> codec;
        private final BiFunction<O, E, O> combiner;

        public Dependent(final MapCodec<O> codec, final MapCodec<E> initialInstance, final Function<O, Pair<E, MapCodec<E>>> splitter, final BiFunction<O, E, O> combiner) {
            this.initialInstance = initialInstance;
            this.splitter = splitter;
            this.codec = codec;
            this.combiner = combiner;
        }

        @Override
        public <T> Stream<T> keys(final DynamicOps<T> ops) {
            return Stream.concat(codec.keys(ops), initialInstance.keys(ops));
        }

        @Override
        public <T> DataResult<O> decode(final DynamicOps<T> ops, final MapLike<T> input) {
            return codec.decode(ops, input).flatMap((O base) ->
                splitter.apply(base).getSecond().decode(ops, input).map(e -> combiner.apply(base, e)).setLifecycle(Lifecycle.experimental())
            );
        }

        @Override
        public <T> RecordBuilder<T> encode(final O input, final DynamicOps<T> ops, final RecordBuilder<T> prefix) {
            codec.encode(input, ops, prefix);
            final Pair<E, MapCodec<E>> e = splitter.apply(input);
            e.getSecond().encode(e.getFirst(), ops, prefix);
            return prefix.setLifecycle(Lifecycle.experimental());
        }
    }

    @Override
    public abstract <T> Stream<T> keys(final DynamicOps<T> ops);

    interface ResultFunction<A> {
        <T> DataResult<A> apply(final DynamicOps<T> ops, final MapLike<T> input, final DataResult<A> a);

        <T> RecordBuilder<T> coApply(final DynamicOps<T> ops, final A input, final RecordBuilder<T> t);
    }

    private MapCodec<A> mapResult(final MapCodec.ResultFunction<A> function) {
        final MapCodec<A> self = this;

        return new MapCodec<A>() {
            @Override
            public <T> Stream<T> keys(final DynamicOps<T> ops) {
                return self.keys(ops);
            }

            @Override
            public <T> RecordBuilder<T> encode(final A input, final DynamicOps<T> ops, final RecordBuilder<T> prefix) {
                return function.coApply(ops, input, self.encode(input, ops, prefix));
            }

            @Override
            public <T> DataResult<A> decode(final DynamicOps<T> ops, final MapLike<T> input) {
                return function.apply(ops, input, self.decode(ops, input));
            }

            @Override
            public String toString() {
                return self + "[mapResult " + function + "]";
            }
        };
    }

    @Override
    public MapCodec<A> withDefault(final Consumer<String> onError, final A value) {
        return withDefault(DataFixUtils.consumerToFunction(onError), value);
    }

    @Override
    public MapCodec<A> withDefault(final Function<String, String> onError, final A value) {
        return mapResult(new ResultFunction<A>() {
            @Override
            public <T> DataResult<A> apply(final DynamicOps<T> ops, final MapLike<T> input, final DataResult<A> a) {
                return DataResult.success(a.mapError(onError).result().orElse(value));
            }

            @Override
            public <T> RecordBuilder<T> coApply(final DynamicOps<T> ops, final A input, final RecordBuilder<T> t) {
                return t.mapError(onError);
            }

            @Override
            public String toString() {
                return "WithDefault[" + onError + " " + value + "]";
            }
        });
    }

    @Override
    public MapCodec<A> withDefault(final Consumer<String> onError, final Supplier<? extends A> value) {
        return withDefault(DataFixUtils.consumerToFunction(onError), value);
    }

    @Override
    public MapCodec<A> withDefault(final Function<String, String> onError, final Supplier<? extends A> value) {
        return mapResult(new ResultFunction<A>() {
            @Override
            public <T> DataResult<A> apply(final DynamicOps<T> ops, final MapLike<T> input, final DataResult<A> a) {
                return DataResult.success(a.mapError(onError).result().orElseGet(value));
            }

            @Override
            public <T> RecordBuilder<T> coApply(final DynamicOps<T> ops, final A input, final RecordBuilder<T> t) {
                return t.mapError(onError);
            }

            @Override
            public String toString() {
                return "WithDefault[" + onError + " " + value.get() + "]";
            }
        });
    }

    @Override
    public MapCodec<A> withDefault(final A value) {
        return mapResult(new ResultFunction<A>() {
            @Override
            public <T> DataResult<A> apply(final DynamicOps<T> ops, final MapLike<T> input, final DataResult<A> a) {
                return DataResult.success(a.result().orElse(value));
            }

            @Override
            public <T> RecordBuilder<T> coApply(final DynamicOps<T> ops, final A input, final RecordBuilder<T> t) {
                return t;
            }

            @Override
            public String toString() {
                return "WithDefault[" + value + "]";
            }
        });
    }

    @Override
    public MapCodec<A> withDefault(final Supplier<? extends A> value) {
        return mapResult(new ResultFunction<A>() {
            @Override
            public <T> DataResult<A> apply(final DynamicOps<T> ops, final MapLike<T> input, final DataResult<A> a) {
                return DataResult.success(a.result().orElseGet(value));
            }

            @Override
            public <T> RecordBuilder<T> coApply(final DynamicOps<T> ops, final A input, final RecordBuilder<T> t) {
                return t;
            }

            @Override
            public String toString() {
                return "WithDefault[" + value.get() + "]";
            }
        });
    }
}
