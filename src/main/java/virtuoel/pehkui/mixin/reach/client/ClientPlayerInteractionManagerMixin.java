package virtuoel.pehkui.mixin.reach.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import virtuoel.pehkui.util.ScaleUtils;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin
{
	@Shadow @Final MinecraftClient client;
	
	@Inject(at = @At("RETURN"), method = "getReachDistance", cancellable = true)
	private void pehkui$getReachDistance(CallbackInfoReturnable<Float> info)
	{
		if (client.player != null)
		{
			final float scale = ScaleUtils.getBlockReachScale(client.player);
			
			if (scale != 1.0F)
			{
				info.setReturnValue(info.getReturnValue() * scale);
			}
		}
	}
}
