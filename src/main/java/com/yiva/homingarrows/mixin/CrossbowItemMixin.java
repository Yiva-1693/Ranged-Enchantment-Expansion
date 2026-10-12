package com.yiva.homingarrows.mixin;

import com.yiva.homingarrows.interop.EnchantmentInterop;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 弩的"无限"附魔互通。
 * <p>
 * 原版 {@code CrossbowItem} <b>完全不读</b>无限附魔（{@code tryLoadProjectiles} 只判断多重射击），
 * 所以弩拿不到无限效果。这里补齐需求：
 * <ul>
 *   <li><b>背包里一支箭都没有也能装填、也能发射</b>（原版 {@code use} 取不到弹药会直接 fail）；</li>
 *   <li><b>装填不消耗背包里的箭</b>。原版消耗发生在 {@code loadProjectile} 里对弹药栈做的
 *       {@code split(1)}，因此只要给原版一份副本，扣掉的就是副本而不是背包那一格。</li>
 * </ul>
 * 注入方式刻意选择"不依赖调用点字符串匹配"的写法：
 * <ul>
 *   <li>{@code use} 用 HEAD 注入直接接管"没箭 + 带无限"这一种情况（自己调用
 *       {@code startUsingItem}），其余一律放行给原版。早先版本改写的是
 *       {@code Player.getProjectile} 这个调用点，依赖方法签名串匹配，实机上出现过"注入没报错、
 *       但行为没生效"的情况，所以这里换掉。</li>
 *   <li>装填侧保留 {@code @Redirect} 并用 {@code require = 1} 显式声明：一旦原版调用形态变化，
 *       会立刻在加载期报错，而不是静默失效。</li>
 * </ul>
 * 非玩家（掠夺者等）一律走原版逻辑。
 */
@Mixin(CrossbowItem.class)
public abstract class CrossbowItemMixin {

    /** 没箭也能开始装填：HEAD 注入接管，其余情况完全交给原版。 */
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void homingarrows$infinityStartCharge(Level level, Player player, InteractionHand hand,
                                                  CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        ItemStack crossbow = player.getItemInHand(hand);
        // 已上弦：交给原版发射流程
        if (CrossbowItem.isCharged(crossbow)) {
            return;
        }
        // 背包里有箭：走原版逻辑（包括多重的取弹判定）
        if (!player.getProjectile(crossbow).isEmpty()) {
            return;
        }
        // 没箭又不带无限：保持原版的 fail
        if (!EnchantmentInterop.hasInfinityForCrossbow(crossbow)) {
            return;
        }
        player.startUsingItem(hand);
        cir.setReturnValue(InteractionResultHolder.consume(crossbow));
    }

    /** 装填：用副本代替背包里的那一格（不消耗），没箭时给一支虚拟箭。 */
    @Redirect(method = "tryLoadProjectiles", require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getProjectile(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack homingarrows$infinityLoadWithoutConsuming(LivingEntity shooter, ItemStack crossbow) {
        ItemStack ammo = shooter.getProjectile(crossbow);
        if (!(shooter instanceof Player) || !EnchantmentInterop.hasInfinityForCrossbow(crossbow)) {
            return ammo;
        }
        return ammo.isEmpty() ? new ItemStack(Items.ARROW) : ammo.copy();
    }
}
