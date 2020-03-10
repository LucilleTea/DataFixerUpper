// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.
package com.mojang.datafixers.types.constant;

import com.mojang.datafixers.types.templates.Const;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DynamicOps;

import java.util.Optional;

public final class FloatType extends Const.PrimitiveType<Float> {
    @Override
    public <T> Pair<T, Optional<Float>> read(final DynamicOps<T> ops, final T input) {
        return ops
            .getNumberValue(input)
            .map(v -> Pair.of(ops.empty(), Optional.of(v.floatValue())))
            .orElseGet(() -> Pair.of(input, Optional.empty()));
    }

    @Override
    public <T> T doWrite(final DynamicOps<T> ops, final Float value) {
        return ops.createFloat(value);
    }

    @Override
    public String toString() {
        return "Float";
    }
}
