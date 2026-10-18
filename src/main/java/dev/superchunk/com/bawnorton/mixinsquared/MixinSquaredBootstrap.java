/*
 * MIT License
 *
 * Copyright (c) 2023-present Bawnorton
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.superchunk.com.bawnorton.mixinsquared;

import dev.superchunk.com.bawnorton.mixinsquared.selector.DynamicSelectorHandler;
import org.spongepowered.asm.mixin.injection.selectors.TargetSelector;

/**
 * SuperChunk: the part of MixinSquared 0.3.3 that SuperChunk uses, {@link TargetHandler}, vendored
 * under dev.superchunk with its selector registered as {@code @SuperChunk:Handler}.
 *
 * <p>SuperChunk used to jar-in-jar mixinsquared-neoforge as group com.github.bawnorton.mixinsquared.
 * A mod that bundles it as com.bawnorton.mixinsquared (GUHAO's VIX does) is a different library to
 * JarJar, so both copies loaded, two modules exported com.bawnorton.mixinsquared.platform.neoforge,
 * and the game could not start. A vendored copy exports nothing under com.bawnorton. It also needs
 * its own namespace: Mixin lets the last registration of a selector id win, so a second
 * "MixinSquared:Handler" would have broken whichever copy registered first. The cancellers and
 * annotation adjusters are not vendored; SuperChunk registers none.
 */
public final class MixinSquaredBootstrap {
    public static final String NAMESPACE = "SuperChunk";

    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;

        initialized = true;

        TargetSelector.register(DynamicSelectorHandler.class, NAMESPACE);
    }
}
