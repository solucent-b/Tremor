package com.example;

import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.Executor;

public class UserService {
    private final Cache<String, String> cache = CacheBuilder.newBuilder().maximumSize(100).build();

    public String describe(String name) {
        if (Strings.isNullOrEmpty(name)) {
            return "nobody";
        }
        return Objects.toStringHelper(this).add("name", name).toString();
    }

    public Executor executor() {
        return MoreExecutors.sameThreadExecutor();
    }

    public ListenableFuture<Integer> length(ListenableFuture<String> in) {
        return Futures.transform(in, (com.google.common.base.Function<String, Integer>) String::length);
    }

    public String cached(String key) {
        return cache.getIfPresent(key);
    }
}
