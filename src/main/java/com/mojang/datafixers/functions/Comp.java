// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.mojang.datafixers.functions;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.types.DynamicOps;
import com.mojang.datafixers.types.Func;
import com.mojang.datafixers.types.Type;

import java.util.Objects;
import java.util.function.Function;

final class Comp<A, B, C> extends PointFree<Function<A, C>> {
    protected final Type<B> middleType;
    protected final PointFree<Function<B, C>> first;
    protected final PointFree<Function<A, B>> second;

    public Comp(final Type<B> middleType, final PointFree<Function<B, C>> first, final PointFree<Function<A, B>> second) {
        this.middleType = middleType;
        this.first = first;
        this.second = second;
    }

    @Override
    public String toString(final int level) {
        return "(\n" + indent(level + 1) + first.toString(level + 1) + "\n" + indent(level + 1) + "◦\n" + indent(level + 1) + second.toString(level + 1) + "\n" + indent(level) + ")";
    }

    @Override
    public PointFree<Function<A, C>> all(final PointFreeRule rule, final Type<Function<A, C>> type) {
        final Func<A, C> funcType = (Func<A, C>) type;
        return Functions.comp(
                middleType,
                DataFixUtils.orElse(rule.rewrite(DSL.func(middleType, funcType.second()), first), first),
                DataFixUtils.orElse(rule.rewrite(DSL.func(funcType.first(), middleType), second), second)
        );
    }

    @Override
    public PointFree<Function<A, C>> one(final PointFreeRule rule, final Type<Function<A, C>> type) {
        final Func<A, C> funcType = (Func<A, C>) type;
        final PointFree<Function<B, C>> result1 = rule.rewrite(DSL.func(middleType, funcType.second()), first);

        if (result1!=null) {
            return Functions.comp(middleType, result1, second);
        }

        final PointFree<Function<A, B>> result2 = rule.rewrite(DSL.func(funcType.first(), middleType), second);

        if (result2!=null) {
            return Functions.comp(middleType, first, result2);
        }

        return null;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final Comp<?, ?, ?> comp = (Comp<?, ?, ?>) o;
        return Objects.equals(first, comp.first) && Objects.equals(second, comp.second);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second);
    }

    @Override
    public Function<DynamicOps<?>, Function<A, C>> eval() {
        return ops -> input -> {
            final Function<A, B> s = second.evalCached().apply(ops);
            final Function<B, C> f = first.evalCached().apply(ops);
            return f.apply(s.apply(input));
        };
    }
}
