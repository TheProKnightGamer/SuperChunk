package dev.superchunk.net.caffeinemc.mods.lithium.mixin.util.item_component_and_count_tracking;

import dev.superchunk.net.caffeinemc.mods.lithium.common.util.change_tracking.ChangePublisher;
import dev.superchunk.net.caffeinemc.mods.lithium.common.util.change_tracking.ChangeSubscriber;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.20.1 re-derive (canary util.item_stack_tracking blueprint): COUNT-ONLY change tracking.
 * DataComponents / PatchedDataComponentMap do not exist on 1.20.1 (NBT era), so the whole
 * component-subscription layer (components shadow, ChangeSubscriber&lt;PatchedDataComponentMap&gt;,
 * onSetComponent / DataComponents.ENCHANTMENTS inject) is dropped. Consumers only rely on
 * count notifications ({@code LithiumStackList.lithium$notify} is a documented no-op for
 * component changes), so the subscription surface is unchanged for the hopper family.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin implements ChangePublisher<ItemStack> {

    @Shadow
    private int count;

    @Shadow
    public abstract boolean isEmpty();

    @Unique
    private ChangeSubscriber<ItemStack> subscriber;
    @Unique
    private int subscriberData;

    @Override
    public void lithium$subscribe(ChangeSubscriber<ItemStack> subscriber, int subscriberData) {
        if (this.isEmpty()) {
            throw new IllegalStateException("Cannot subscribe to an empty ItemStack!");
        }

        this.subscriber = ChangeSubscriber.combine(this.subscriber, this.subscriberData, subscriber, subscriberData);
        if (this.subscriber instanceof ChangeSubscriber.Multi<?>) {
            this.subscriberData = 0;
        } else {
            this.subscriberData = subscriberData;
        }
    }

    @Override
    public int lithium$unsubscribe(ChangeSubscriber<ItemStack> subscriber) {
        if (this.isEmpty()) {
            throw new IllegalStateException("Cannot unsubscribe from an empty ItemStack!");
        }

        int retval = ChangeSubscriber.dataOf(this.subscriber, subscriber, this.subscriberData);
        this.subscriberData = ChangeSubscriber.dataWithout(this.subscriber, subscriber, this.subscriberData);
        this.subscriber = ChangeSubscriber.without(this.subscriber, subscriber);
        return retval;
    }

    @Override
    public void lithium$unsubscribeWithData(ChangeSubscriber<ItemStack> subscriber, int subscriberData) {
        if (this.isEmpty()) {
            throw new IllegalStateException("Cannot unsubscribe from an empty ItemStack!");
        }

        this.subscriberData = ChangeSubscriber.dataWithout(this.subscriber, subscriber, this.subscriberData, subscriberData, true);
        this.subscriber = ChangeSubscriber.without(this.subscriber, subscriber, subscriberData, true);
    }

    @Override
    public boolean lithium$isSubscribedWithData(ChangeSubscriber<ItemStack> subscriber, int subscriberData) {
        if (this.isEmpty()) {
            throw new IllegalStateException("Cannot be subscribed to an empty ItemStack!");
        }

        return ChangeSubscriber.containsSubscriber(this.subscriber, this.subscriberData, subscriber, subscriberData);
    }

    @Inject(method = "setCount(I)V", at = @At("HEAD"))
    private void beforeChangeCount(int count, CallbackInfo ci) {
        if (count != this.count) {

            if (this.subscriber instanceof ChangeSubscriber.CountChangeSubscriber<ItemStack> countChangeSubscriber) {
                countChangeSubscriber.lithium$notifyCount((ItemStack) (Object) this, this.subscriberData, count);
            }

            if (count == 0) {
                if (this.subscriber != null) {
                    this.subscriber.lithium$forceUnsubscribe((ItemStack) (Object) this, this.subscriberData);
                    this.subscriber = null;
                    this.subscriberData = 0;
                }
            }
        }

    }
}
