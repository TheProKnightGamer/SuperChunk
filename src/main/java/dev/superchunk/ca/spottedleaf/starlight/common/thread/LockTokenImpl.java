package dev.superchunk.ca.spottedleaf.starlight.common.thread;

import dev.superchunk.com.ishland.flowsched.executor.LockToken;

public record LockTokenImpl(int ownerTag, long pos) implements LockToken {
}
