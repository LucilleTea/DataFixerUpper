// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.mojang.datafixers.functions;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.types.DynamicOps;
import com.mojang.datafixers.types.Type;

import java.util.Objects;
import java.util.function.Function;

final class Apply<A, B> extends PointFree<B> {
    protected final PointFree<Function<A, B>> func;
    protected final PointFree<A> arg;
    protected final Type<A> argType;

    public Apply(final PointFree<Function<A, B>> func, final PointFree<A> arg, final Type<A> argType) {
        this.func = func;
        this.arg = arg;
        this.argType = argType;
    }

    @Override
    public Function<DynamicOps<?>, B> eval() {
        return ops -> func.evalCached().apply(ops).apply(arg.evalCached().apply(ops));
    }

    @Override
    public String toString(final int level) {
        return "(ap " + func.toString(level + 1) + "\n" + indent(level + 1) + arg.toString(level + 1) + "\n" + indent(level) + ")";
    }

    @Override
    public PointFree<B> all(final PointFreeRule rule, final Type<B> type) {
        return Functions.app(
                DataFixUtils.orElse(rule.rewrite(DSL.func(argType, type), func), func),
                DataFixUtils.orElse(rule.rewrite(argType, arg), arg),
                argType
        );
    }

    @Override
    public PointFree<B> one(final PointFreeRule rule, final Type<B> type) {
        final PointFree<Function<A, B>> result1 = rule.rewrite(DSL.func(argType, type), func);

        if (result1!=null) {
            return Functions.app(result1, arg, argType);
        }

        final PointFree<A> result2 = rule.rewrite(argType, arg);

        if (result2!=null) {
            return Functions.app(func, result2, argType);
        }

        return null;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Apply<?, ?>)) {
            return false;
        }
        final Apply<?, ?> apply = (Apply<?, ?>) o;
        return Objects.equals(func, apply.func) && Objects.equals(arg, apply.arg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(func, arg);
    }
}
