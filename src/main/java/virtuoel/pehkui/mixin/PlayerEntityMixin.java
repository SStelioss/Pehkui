package virtuoel.pehkui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Desc;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import virtuoel.pehkui.util.ScaleUtils;

@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin
{
	@Inject(at = @At("RETURN"), method = "getDimensions", cancellable = true)
	private void pehkui$getDimensions(EntityPose pose, CallbackInfoReturnable<EntityDimensions> info)
	{
		info.setReturnValue(info.getReturnValue().scaled(ScaleUtils.getBoundingBoxWidthScale((Entity) (Object) this), ScaleUtils.getBoundingBoxHeightScale((Entity) (Object) this)));
	}
	
	@ModifyArg(method = "tickMovement", index = 1, at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(FF)F"))
	private float pehkui$tickMovement$mMinVelocity(float velocity)
	{
		return velocity * ScaleUtils.getMotionScale((Entity) (Object) this);
	}
	
	@Inject(method = "travel(Lnet/minecraft/util/math/Vec3d;)V", at = @At(value = "INVOKE", ordinal = 0, shift = Shift.BEFORE, target = "Lnet/minecraft/entity/LivingEntity;travel(Lnet/minecraft/util/math/Vec3d;)V"))
	private void pehkui$travel$flightSpeed(Vec3d movementInput, CallbackInfo info)
	{
		final float scale = ScaleUtils.getFlightScale((Entity) (Object) this);
		
		if (scale != 1.0F)
		{
			((PlayerEntity) (Object) this).airStrafingSpeed *= scale;
		}
	}
	
	@Inject(at = @At("RETURN"), method = "dropItem(Lnet/minecraft/item/ItemStack;ZZ)Lnet/minecraft/entity/ItemEntity;")
	private void pehkui$dropItem(ItemStack stack, boolean spread, boolean thrown, CallbackInfoReturnable<ItemEntity> info)
	{
		final ItemEntity entity = info.getReturnValue();
		
		if (entity != null)
		{
			ScaleUtils.setScaleOfDrop(entity, (Entity) (Object) this);
			
			final float scale = ScaleUtils.getEyeHeightScale((Entity) (Object) this);
			
			if (scale != 1.0F)
			{
				final Vec3d pos = entity.getPos();
				
				entity.updatePosition(pos.x, pos.y + ((1.0F - scale) * 0.3D), pos.z);
			}
		}
	}
	
	@ModifyArg(method = "tickMovement()V", index = 0, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/Box;expand(DDD)Lnet/minecraft/util/math/Box;"))
	private double pehkui$tickMovement$expand$x(double value)
	{
		return value * ScaleUtils.getMotionScale((Entity) (Object) this);
	}
	
	@ModifyArg(method = "tickMovement()V", index = 1, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/Box;expand(DDD)Lnet/minecraft/util/math/Box;"))
	private double pehkui$tickMovement$expand$y(double value)
	{
		return value * ScaleUtils.getMotionScale((Entity) (Object) this);
	}
	
	@ModifyArg(method = "tickMovement()V", index = 2, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/Box;expand(DDD)Lnet/minecraft/util/math/Box;"))
	private double pehkui$tickMovement$expand$z(double value)
	{
		return value * ScaleUtils.getMotionScale((Entity) (Object) this);
	}
	
	@ModifyConstant(method = "attack(Lnet/minecraft/entity/Entity;)V", constant = { @Constant(floatValue = 0.5F, ordinal = 1), @Constant(floatValue = 0.5F, ordinal = 2), @Constant(floatValue = 0.5F, ordinal = 3) })
	private float pehkui$attack$knockback(float value)
	{
		final float scale = ScaleUtils.getKnockbackScale((Entity) (Object) this);
		
		return scale != 1.0F ? scale * value : value;
	}
	
	@ModifyConstant(method = "getAttackCooldownProgressPerTick", constant = @Constant(doubleValue = 20.0D))
	private double pehkui$getAttackCooldownProgressPerTick$multiplier(double value)
	{
		final float scale = ScaleUtils.getAttackSpeedScale((Entity) (Object) this);
		
		return scale != 1.0F ? value / scale : value;
	}
	
	@Inject(at = @At("RETURN"), target = @Desc(value = "getDigSpeed", args = { BlockState.class, BlockPos.class }, ret = float.class), cancellable = true)
	private void pehkui$getBlockBreakingSpeed(BlockState block, BlockPos pos, CallbackInfoReturnable<Float> info)
	{
		final float scale = ScaleUtils.getMiningSpeedScale((Entity) (Object) this);
		
		if (scale != 1.0F)
		{
			info.setReturnValue(info.getReturnValueF() * scale);
		}
	}
	
	@ModifyConstant(method = "updateCapeAngles", constant = { @Constant(doubleValue = 10.0D), @Constant(doubleValue = -10.0D) })
	private double pehkui$updateCapeAngles$limits(double value)
	{
		final float scale = ScaleUtils.getMotionScale((Entity) (Object) this);
		
		return scale != 1.0F ? scale * value : value;
	}
}
