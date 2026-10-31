package com.bawnorton.mixinsquared.canceller;

import com.bawnorton.mixinsquared.api.MixinCanceller;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Test stand-in for MixinSquared 0.3's registrar: the same public static methods. */
public final class MixinCancellerRegistrar {
    private static final Set<MixinCanceller> cancellers = new LinkedHashSet<>();

    public static boolean shouldCancel(List<String> targetClassNames, String mixinClassName, Consumer<String> onCancel) {
        return cancellers.stream().anyMatch(canceller -> {
            boolean cancel = canceller.shouldCancel(targetClassNames, mixinClassName);
            if (cancel) onCancel.accept(canceller.getClass().getName());
            return cancel;
        });
    }

    public static void register(MixinCanceller canceller) {
        cancellers.add(canceller);
    }
}
