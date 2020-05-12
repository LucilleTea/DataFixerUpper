package com.mojang.datafixers.util;

import com.mojang.datafixers.types.families.RecursiveTypeFamily;
import com.mojang.datafixers.types.templates.*;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class Pool<T> {
    private static final ArrayList<Pool<?>> POOLS = new ArrayList<>();

    public static final Pool<Sum> SUM_POOL = createPool();
    public static final Pool<TaggedChoice<?>> TAGGED_CHOICE_POOL = createPool();
    public static final Pool<Check> CHECK_POOL = createPool();
    public static final Pool<Named> NAMED_POOL = createPool();
    public static final Pool<Product> PRODUCT_POOL = createPool();
    public static final Pool<CompoundList> COMPOUND_LIST_POOL = createPool();
    public static final Pool<RecursiveTypeFamily> RECURSIVE_TYPE_FAMILY_POOL = createPool();
    public static final Pool<Hook> HOOK_POOL = createPool();
    public static final Pool<List> LIST_POOL = createPool();
    public static final Pool<Tag> TAG_POOL = createPool();
    public static final Pool<Const> CONST_POOL = createPool();
    public static final Pool<RecursivePoint> RECURSIVE_POINT_POOL = createPool();

    private final ConcurrentHashMap<Supplier<T>, T> pool = new ConcurrentHashMap<>();

    public T create(Supplier<T> supplier) {
        return this.pool.computeIfAbsent(supplier, Supplier::get);
    }

    public void clear() {
        this.pool.clear();
    }

    public static void clearPools() {
        for (Pool<?> pool : POOLS) {
            pool.clear();
        }
    }

    private static <T> Pool<T> createPool() {
        Pool<T> pool = new Pool<>();
        POOLS.add(pool);

        return pool;
    }
}
