package com.seibel.distanthorizons.api.methods.events.abstractEvents;

import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public abstract class DhApiChunkProcessingEvent {
    public static class EventParam {
        public EventParam(IDhApiLevelWrapper levelWrapper, int chunkX, int chunkZ) {
            throw new UnsupportedOperationException();
        }

        public void updateForPosition(int relativeBlockPosX, int blockPosY, int relativeBlockPosZ,
                                      IDhApiBlockStateWrapper currentBlock, IDhApiBiomeWrapper currentBiome) {
            throw new UnsupportedOperationException();
        }

        public IDhApiBlockStateWrapper getBlockOverride() {
            throw new UnsupportedOperationException();
        }

        public IDhApiBiomeWrapper getBiomeOverride() {
            throw new UnsupportedOperationException();
        }
    }
}
