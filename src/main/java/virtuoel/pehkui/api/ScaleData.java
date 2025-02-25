package virtuoel.pehkui.api;

import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.floats.Float2FloatFunction;
import it.unimi.dsi.fastutil.objects.ObjectAVLTreeSet;
import net.fabricmc.fabric.api.util.NbtType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import virtuoel.pehkui.util.PehkuiEntityExtensions;

public class ScaleData
{
	private float baseScale;
	private float prevBaseScale;
	private float initialScale;
	private float targetScale;
	private int scaleTicks;
	private int totalScaleTicks;
	private Boolean persistent = null;
	private Float2FloatFunction easing = null;
	
	private boolean shouldSync;
	
	private final ScaleType scaleType;
	
	@Nullable
	private final Entity entity;
	
	private final SortedSet<ScaleModifier> baseValueModifiers;
	private final SortedSet<ScaleModifier> differingModifierCache;
	private boolean trackModifierChanges;
	private float cachedScale = Float.NaN;
	private float cachedPrevScale = Float.NaN;
	
	/**
	 * @see {@link ScaleType#getScaleData(Entity)}
	 * @see {@link ScaleData.Builder#create()}
	 */
	@SuppressWarnings("serial")
	@ApiStatus.Internal
	protected ScaleData(ScaleType scaleType, @Nullable Entity entity)
	{
		this.scaleType = scaleType;
		this.entity = entity;
		
		final float defaultBaseScale = scaleType.getDefaultBaseScale();
		
		this.baseScale = defaultBaseScale;
		this.prevBaseScale = defaultBaseScale;
		this.initialScale = defaultBaseScale;
		this.targetScale = defaultBaseScale;
		this.scaleTicks = 0;
		this.totalScaleTicks = scaleType.getDefaultTickDelay();
		
		this.shouldSync = false;
		
		this.baseValueModifiers = new ObjectAVLTreeSet<ScaleModifier>()
		{
			@Override
			public boolean add(ScaleModifier arg0)
			{
				if (super.add(arg0))
				{
					invalidateCachedScales();
					
					if (ScaleData.this.trackModifierChanges)
					{
						invalidateCachedModifiers();
						onUpdate();
					}
					
					return true;
				}
				
				return false;
			}
			
			@Override
			public boolean remove(Object arg0)
			{
				if (super.remove(arg0))
				{
					invalidateCachedScales();
					
					if (ScaleData.this.trackModifierChanges)
					{
						invalidateCachedModifiers();
						onUpdate();
					}
					
					return true;
				}
				
				return false;
			}
		};
		
		this.differingModifierCache = new ObjectAVLTreeSet<>();
		
		this.trackModifierChanges = false;
		
		getBaseValueModifiers().addAll(getScaleType().getDefaultBaseValueModifiers());
		
		this.trackModifierChanges = true;
	}
	
	/**
	 * Called at the start of {@link Entity#tick()}.
	 * <p>Pre and post tick callbacks are not invoked here. If calling this manually, be sure to invoke callbacks!
	 */
	public void tick()
	{
		invalidateCachedScales();
		
		final float currScale = getBaseScale();
		final float targetScale = getTargetScale();
		
		if (currScale != targetScale)
		{
			final int scaleTickDelay = getScaleTickDelay();
			
			if (this.scaleTicks >= scaleTickDelay)
			{
				this.initialScale = targetScale;
				this.scaleTicks = 0;
				setBaseScale(targetScale);
			}
			else
			{
				this.scaleTicks++;
				setBaseScale(calculateScaleForTick(this.scaleTicks, 0));
			}
		}
		else
		{
			if (this.prevBaseScale != currScale)
			{
				this.prevBaseScale = currScale;
				
				this.cachedPrevScale = Float.NaN;
			}
			
			if (this.initialScale != targetScale)
			{
				this.initialScale = targetScale;
			}
			
			if (this.scaleTicks != 0)
			{
				this.scaleTicks = 0;
			}
		}
	}
	
	public ScaleType getScaleType()
	{
		return this.scaleType;
	}
	
	@Nullable
	public Entity getEntity()
	{
		return this.entity;
	}
	
	/**
	 * Returns a mutable sorted set of scale modifiers. This set already contains the default modifiers from the scale type.
	 * @return Set of scale modifiers sorted by priority
	 */
	public SortedSet<ScaleModifier> getBaseValueModifiers()
	{
		return baseValueModifiers;
	}
	
	/**
	 * Gets the scale without any modifiers applied
	 * 
	 * @return Scale without any modifiers applied
	 */
	public float getBaseScale()
	{
		return getBaseScale(1.0F);
	}
	
	/**
	 * Gets the scale without any modifiers applied
	 * 
	 * @param delta Tick delta for use with rendering. Use 1.0F if no delta is available.
	 * @return Scale without any modifiers applied
	 */
	public float getBaseScale(float delta)
	{
		return delta == 1.0F ? baseScale : calculateScaleForTick(scaleTicks, delta);
	}
	
	/**
	 * Sets the scale to the given value, updates the previous scale, and notifies listeners
	 * 
	 * @param scale New scale value to be set
	 */
	public void setBaseScale(float scale)
	{
		scale = (float) getScaleType().clampBaseScale(this, scale);
		
		this.prevBaseScale = getBaseScale();
		this.baseScale = scale;
		onUpdate();
	}
	
	/**
	 * Gets the scale with modifiers applied
	 * 
	 * @return Scale with modifiers applied
	 */
	public float getScale()
	{
		return getScale(1.0F);
	}
	
	/**
	 * Gets the scale with modifiers applied
	 * 
	 * @param delta Tick delta for use with rendering. Use 1.0F if no delta is available.
	 * @return Scale with modifiers applied
	 */
	public float getScale(float delta)
	{
		final Entity e = getEntity();
		final boolean canCache = delta == 1.0F && e != null && e.world != null && !e.world.isClient && (e.getType() != EntityType.PLAYER || !getScaleType().getAffectsDimensions()) && !((PehkuiEntityExtensions) e).pehkui_isFirstUpdate();
		
		if (canCache && !Float.isNaN(cachedScale))
		{
			return cachedScale;
		}
		
		float value = getBaseScale(delta);
		
		for (final ScaleModifier m : getBaseValueModifiers())
		{
			value = m.modifyScale(this, value, delta);
		}
		
		if (canCache)
		{
			cachedScale = value;
		}
		
		return value;
	}
	
	/**
	 * Helper for instant resizing that sets both the base scale and target scale.
	 * 
	 * @param scale New scale value to be set
	 */
	public void setScale(float scale)
	{
		setBaseScale(scale);
		setTargetScale(scale);
	}
	
	public float getInitialScale()
	{
		return this.initialScale;
	}
	
	public float getTargetScale()
	{
		return this.targetScale;
	}
	
	/**
	 * Sets a target scale. The base scale will be gradually changed to this over the amount of ticks specified by the scale tick delay.
	 * 
	 * @param targetScale The scale that the base scale should gradually change to
	 */
	public void setTargetScale(float targetScale)
	{
		targetScale = (float) getScaleType().clampTargetScale(this, targetScale);
		
		this.scaleTicks = calculateRemainingScaleTicks();
		this.initialScale = getTargetScale();
		this.targetScale = targetScale;
		
		markForSync(true);
	}
	
	@ApiStatus.Internal
	@ApiStatus.NonExtendable
	protected float calculateScaleForTick(int ticks, float delta)
	{
		final Float2FloatFunction easing = Optional.ofNullable(getEasing()).orElseGet(getScaleType()::getDefaultEasing);
		
		final float progress = (float) ticks + delta;
		final int total = getScaleTickDelay();
		final float range = getTargetScale() - this.initialScale;
		final float perTick = total == 0 ? 1.0F : (easing.apply(progress / total));
		final float next = this.initialScale + (perTick * range);
		
		return next;
	}
	
	@ApiStatus.Internal
	@ApiStatus.NonExtendable
	protected int calculateRemainingScaleTicks()
	{
		final float lastTarget = getTargetScale();
		final float initial = getInitialScale();
		
		final int remaining = lastTarget == initial ? 0 : Math.round(getScaleTickDelay() * ((lastTarget - getBaseScale()) / (lastTarget - initial)));
		
		return remaining;
	}
	
	/**
	 * Gets the amount of ticks it will take for the base scale to change to the target scale
	 * 
	 * @return Delay in ticks
	 */
	public int getScaleTickDelay()
	{
		return this.totalScaleTicks;
	}
	
	/**
	 * Sets the amount of ticks it will take for the base scale to change to the target scale
	 * 
	 * @param ticks Delay in ticks
	 */
	public void setScaleTickDelay(int ticks)
	{
		this.totalScaleTicks = ticks;
		markForSync(true);
	}
	
	/**
	 * Gets the last value that the base scale was set to with modifiers applied. Useful for linear interpolation.
	 * 
	 * @return Last value of the base scale with modifiers applied
	 */
	public float getPrevScale()
	{
		if (!Float.isNaN(cachedPrevScale))
		{
			return cachedPrevScale;
		}
		
		float value = getPrevBaseScale();
		
		for (final ScaleModifier m : getBaseValueModifiers())
		{
			value = m.modifyPrevScale(this, value);
		}
		
		cachedPrevScale = value;
		
		return value;
	}
	
	/**
	 * Gets the last value that the base scale was set to. Useful for linear interpolation.
	 * 
	 * @return Last value of the base scale
	 */
	public float getPrevBaseScale()
	{
		return this.prevBaseScale;
	}
	
	public void setPersistence(@Nullable Boolean persistent)
	{
		this.persistent = persistent;
		markForSync(true);
	}
	
	public @Nullable Boolean getPersistence()
	{
		return persistent;
	}
	
	public boolean shouldPersist()
	{
		final Boolean persist = getPersistence();
		return persist == null ? getScaleType().getDefaultPersistence() : persist;
	}
	
	public @Nullable Float2FloatFunction getEasing()
	{
		return this.easing;
	}
	
	public void setEasing(@Nullable Float2FloatFunction easing)
	{
		this.easing = easing;
		markForSync(true);
	}
	
	public void markForSync(boolean sync)
	{
		final Entity e = getEntity();
		
		if (e != null && e.world != null && !e.world.isClient)
		{
			this.shouldSync = sync;
			if (this.shouldSync)
			{
				((PehkuiEntityExtensions) e).pehkui_setShouldSyncScales(true);
			}
		}
	}
	
	public boolean shouldSync()
	{
		return this.shouldSync;
	}
	
	/**
	 * Marks this to be synced to clients and also invokes scale change events.
	 * <p>Gets called by methods that modify the scale. Doesn't typically need to be called from outside.
	 */
	public void onUpdate()
	{
		invalidateCachedScales();
		markForSync(true);
		getScaleType().getScaleChangedEvent().invoker().onEvent(this);
	}
	
	private void invalidateCachedModifiers()
	{
		this.differingModifierCache.clear();
		this.differingModifierCache.addAll(getBaseValueModifiers());
		this.differingModifierCache.removeAll(getScaleType().getDefaultBaseValueModifiers());
	}
	
	private void invalidateCachedScales()
	{
		this.cachedScale = Float.NaN;
		this.cachedPrevScale = Float.NaN;
	}
	
	public PacketByteBuf toPacket(PacketByteBuf buffer)
	{
		buffer.writeFloat(this.baseScale)
		.writeFloat(this.prevBaseScale)
		.writeFloat(this.initialScale)
		.writeFloat(this.targetScale)
		.writeInt(this.scaleTicks)
		.writeInt(this.totalScaleTicks)
		.writeInt(this.differingModifierCache.size());
		
		for (final ScaleModifier modifier : this.differingModifierCache)
		{
			buffer.writeIdentifier(ScaleRegistries.getId(ScaleRegistries.SCALE_MODIFIERS, modifier));
		}
		
		buffer.writeByte(this.persistent == null ? -1 : this.persistent ? 1 : 0);
		
		if (this.easing != null)
		{
			buffer.writeBoolean(true);
			buffer.writeIdentifier(ScaleRegistries.getId(ScaleRegistries.SCALE_EASINGS, this.easing));
		}
		else
		{
			buffer.writeBoolean(false);
		}
		
		return buffer;
	}
	
	public void readNbt(NbtCompound tag)
	{
		final ScaleType type = getScaleType();
		
		this.baseScale = tag.contains("scale") ? tag.getFloat("scale") : type.getDefaultBaseScale();
		this.prevBaseScale = tag.contains("previous") ? tag.getFloat("previous") : this.baseScale;
		this.initialScale = tag.contains("initial") ? tag.getFloat("initial") : this.baseScale;
		this.targetScale = tag.contains("target") ? tag.getFloat("target") : this.baseScale;
		
		this.scaleTicks = tag.contains("ticks") ? tag.getInt("ticks") : 0;
		this.totalScaleTicks = tag.contains("total_ticks") ? tag.getInt("total_ticks") : type.getDefaultTickDelay();
		
		this.persistent = tag.contains("persistent") ? tag.getBoolean("persistent") : null;
		
		this.easing = tag.contains("easing") ? ScaleRegistries.getEntry(ScaleRegistries.SCALE_EASINGS, Identifier.tryParse(tag.getString("easing"))) : null;
		
		this.trackModifierChanges = false;
		
		final SortedSet<ScaleModifier> baseValueModifiers = getBaseValueModifiers();
		
		baseValueModifiers.clear();
		
		baseValueModifiers.addAll(type.getDefaultBaseValueModifiers());
		
		if (tag.contains("baseValueModifiers"))
		{
			final NbtList modifiers = tag.getList("baseValueModifiers", NbtType.STRING);
			
			Identifier id;
			ScaleModifier modifier;
			NbtElement element;
			for (int i = 0; i < modifiers.size(); i++)
			{
				element = modifiers.get(i);
				if (element.getType() == NbtElement.STRING_TYPE)
				{
					id = Identifier.tryParse(modifiers.getString(i));
					modifier = ScaleRegistries.getEntry(ScaleRegistries.SCALE_MODIFIERS, id);
				}
				else if (element.getType() == NbtElement.COMPOUND_TYPE)
				{
					final NbtCompound compound = modifiers.getCompound(i);
					id = Identifier.tryParse(compound.getString("id"));
					modifier = ScaleRegistries.getEntry(ScaleRegistries.SCALE_MODIFIERS, id);
				}
				else
				{
					modifier = null;
				}
				
				if (modifier != null)
				{
					baseValueModifiers.add(modifier);
				}
			}
			
			invalidateCachedModifiers();
		}
		else
		{
			this.differingModifierCache.clear();
		}
		
		this.trackModifierChanges = true;
		
		onUpdate();
	}
	
	public NbtCompound writeNbt(NbtCompound tag)
	{
		final ScaleType type = getScaleType();
		final float defaultBaseScale = type.getDefaultBaseScale();
		
		final float scale = getBaseScale();
		if (scale != defaultBaseScale)
		{
			tag.putFloat("scale", scale);
		}
		
		final float initial = getInitialScale();
		if (initial != defaultBaseScale)
		{
			tag.putFloat("initial", initial);
		}
		
		final float target = getTargetScale();
		if (target != defaultBaseScale)
		{
			tag.putFloat("target", target);
		}
		
		if (this.scaleTicks != 0)
		{
			tag.putInt("ticks", this.scaleTicks);
		}
		
		if (this.totalScaleTicks != type.getDefaultTickDelay())
		{
			tag.putInt("total_ticks", this.totalScaleTicks);
		}
		
		final Boolean persistent = getPersistence();
		if (persistent != null)
		{
			tag.putBoolean("persistent", persistent);
		}
		
		final Float2FloatFunction easing = getEasing();
		if (easing != null)
		{
			tag.put("easing", NbtOps.INSTANCE.createString(ScaleRegistries.getId(ScaleRegistries.SCALE_EASINGS, easing).toString()));
		}
		
		if (!this.differingModifierCache.isEmpty())
		{
			final NbtList modifiers = new NbtList();
			
			for (final ScaleModifier modifier : this.differingModifierCache)
			{
				modifiers.add(NbtOps.INSTANCE.createString(ScaleRegistries.getId(ScaleRegistries.SCALE_MODIFIERS, modifier).toString()));
			}
			
			tag.put("baseValueModifiers", modifiers);
		}
		
		return tag;
	}
	
	public ScaleData resetScale()
	{
		return resetScale(true);
	}
	
	public ScaleData resetScale(boolean notifyListener)
	{
		final ScaleType type = getScaleType();
		final float defaultBaseScale = type.getDefaultBaseScale();
		
		this.baseScale = defaultBaseScale;
		this.prevBaseScale = defaultBaseScale;
		this.initialScale = defaultBaseScale;
		this.targetScale = defaultBaseScale;
		this.scaleTicks = 0;
		this.totalScaleTicks = type.getDefaultTickDelay();
		this.persistent = null;
		this.easing = null;
		
		invalidateCachedScales();
		
		this.trackModifierChanges = false;
		
		final SortedSet<ScaleModifier> baseValueModifiers = getBaseValueModifiers();
		
		baseValueModifiers.clear();
		baseValueModifiers.addAll(type.getDefaultBaseValueModifiers());
		
		this.differingModifierCache.clear();
		
		this.trackModifierChanges = true;
		
		if (notifyListener)
		{
			onUpdate();
		}
		
		return this;
	}
	
	public boolean isReset()
	{
		return hasDefaultValues();
	}
	
	@ApiStatus.Internal
	public boolean hasDefaultValues()
	{
		final ScaleType type = getScaleType();
		final float defaultBaseScale = type.getDefaultBaseScale();
		
		if (getBaseScale() != defaultBaseScale)
		{
			return false;
		}
		
		if (this.prevBaseScale != defaultBaseScale)
		{
			return false;
		}
		
		if (getInitialScale() != defaultBaseScale)
		{
			return false;
		}
		
		if (getTargetScale() != defaultBaseScale)
		{
			return false;
		}
		
		if (this.scaleTicks != 0)
		{
			return false;
		}
		
		if (getScaleTickDelay() != type.getDefaultTickDelay())
		{
			return false;
		}
		
		if (!this.differingModifierCache.isEmpty())
		{
			return false;
		}
		
		if (getPersistence() != null)
		{
			return false;
		}
		
		if (getEasing() != null)
		{
			return false;
		}
		
		return true;
	}
	
	public ScaleData fromScale(ScaleData scaleData)
	{
		return fromScale(scaleData, true);
	}
	
	public ScaleData fromScale(ScaleData scaleData, boolean notifyListener)
	{
		if (scaleData != this)
		{
			this.baseScale = scaleData.getBaseScale();
			this.prevBaseScale = scaleData.getPrevBaseScale();
			this.initialScale = scaleData.getInitialScale();
			this.targetScale = scaleData.getTargetScale();
			this.scaleTicks = scaleData.scaleTicks;
			this.totalScaleTicks = scaleData.totalScaleTicks;
			this.persistent = scaleData.getPersistence();
			this.easing = scaleData.getEasing();
			
			invalidateCachedScales();
		}
		
		if (notifyListener)
		{
			onUpdate();
		}
		
		return this;
	}
	
	/**
	 * Averages the values of the given scale data and sets its own values from them.
	 * 
	 * @param scaleData Single scale data
	 * @param scales Any additional scale data
	 * @return Itself
	 */
	public ScaleData averagedFromScales(ScaleData scaleData, ScaleData... scales)
	{
		float scale = scaleData.getBaseScale();
		float prevScale = scaleData.prevBaseScale;
		float fromScale = scaleData.getInitialScale();
		float toScale = scaleData.getTargetScale();
		int scaleTicks = scaleData.scaleTicks;
		int totalScaleTicks = scaleData.totalScaleTicks;
		
		for (final ScaleData data : scales)
		{
			scale += data.getBaseScale();
			prevScale += data.prevBaseScale;
			fromScale += data.getInitialScale();
			toScale += data.getTargetScale();
			scaleTicks += data.scaleTicks;
			totalScaleTicks += data.totalScaleTicks;
		}
		
		final float count = scales.length + 1;
		
		this.baseScale = scale / count;
		this.prevBaseScale = prevScale / count;
		this.initialScale = fromScale / count;
		this.targetScale = toScale / count;
		this.scaleTicks = Math.round(scaleTicks / count);
		this.totalScaleTicks = Math.round(totalScaleTicks / count);
		
		onUpdate();
		
		return this;
	}
	
	@Override
	public int hashCode()
	{
		return Objects.hash(initialScale, prevBaseScale, baseScale, scaleTicks, targetScale, totalScaleTicks);
	}
	
	@Override
	public boolean equals(final Object obj)
	{
		if (this == obj)
		{
			return true;
		}
		
		if (!(obj instanceof ScaleData))
		{
			return false;
		}
		
		return equals((ScaleData) obj);
	}
	
	public boolean equals(final ScaleData other)
	{
		if (this == other)
		{
			return true;
		}
		
		return Float.floatToIntBits(baseScale) == Float.floatToIntBits(other.baseScale) &&
			Float.floatToIntBits(prevBaseScale) == Float.floatToIntBits(other.prevBaseScale) &&
			Float.floatToIntBits(initialScale) == Float.floatToIntBits(other.initialScale) &&
			Float.floatToIntBits(targetScale) == Float.floatToIntBits(other.targetScale) &&
			scaleTicks == other.scaleTicks &&
			totalScaleTicks == other.totalScaleTicks &&
			Float.floatToIntBits(getScale()) == Float.floatToIntBits(other.getScale());
	}
	
	public static class Builder
	{
		private Entity entity = null;
		private ScaleType type = ScaleTypes.INVALID;
		
		public static Builder create()
		{
			return new Builder();
		}
		
		private Builder()
		{
			
		}
		
		public Builder type(ScaleType type)
		{
			this.type = type == null ? ScaleTypes.INVALID : type;
			return this;
		}
		
		public Builder entity(@Nullable Entity entity)
		{
			this.entity = entity;
			return this;
		}
		
		public ScaleData build()
		{
			final ScaleData existing = entity == null ? null : type.getScaleData(entity);
			
			return existing != null ? existing : new ScaleData(type, entity);
		}
	}
}
