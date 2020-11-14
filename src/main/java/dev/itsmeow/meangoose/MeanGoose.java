package dev.itsmeow.meangoose;

import java.util.EnumSet;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import its_meow.betteranimalsplus.common.entity.EntityGoose;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.EntitySummonArgument;
import net.minecraft.command.arguments.NBTCompoundTagArgument;
import net.minecraft.command.arguments.SuggestionProviders;
import net.minecraft.command.arguments.Vec3Argument;
import net.minecraft.entity.CreatureEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPredicate;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ILivingEntityData;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.HurtByTargetGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.TargetGoal;
import net.minecraft.entity.effect.LightningBoltEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Hand;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;

@Mod(value = MeanGoose.MODID)
public class MeanGoose {

    public static final String MODID = "meangoose";
    private static final SimpleCommandExceptionType SUMMON_FAILED = new SimpleCommandExceptionType(new TranslationTextComponent("commands.summon.failed"));

    public MeanGoose() {
        MinecraftForge.EVENT_BUS.addListener((final FMLServerStartingEvent event) -> {
            CommandDispatcher<CommandSource> d = event.getCommandDispatcher();
            d.register(Commands.literal("meangoose").requires((source) -> {
                return source.hasPermissionLevel(2);
             }).then(Commands.argument("entity", EntitySummonArgument.entitySummon()).suggests(SuggestionProviders.SUMMONABLE_ENTITIES).executes((ctx) -> {
                return summonEntity(ctx.getSource(), EntitySummonArgument.getEntityId(ctx, "entity"), ctx.getSource().getPos(), new CompoundNBT(), true);
             }).then(Commands.argument("pos", Vec3Argument.vec3()).executes((ctx) -> {
                return summonEntity(ctx.getSource(), EntitySummonArgument.getEntityId(ctx, "entity"), Vec3Argument.getVec3(ctx, "pos"), new CompoundNBT(), true);
             }).then(Commands.argument("nbt", NBTCompoundTagArgument.nbt()).executes((ctx) -> {
                return summonEntity(ctx.getSource(), EntitySummonArgument.getEntityId(ctx, "entity"), Vec3Argument.getVec3(ctx, "pos"), NBTCompoundTagArgument.getNbt(ctx, "nbt"), false);
             })))));
        });
    }

    private static int summonEntity(CommandSource source, ResourceLocation type, Vec3d pos, CompoundNBT nbt, boolean randomizeProperties) throws CommandSyntaxException {
        CompoundNBT compoundnbt = nbt.copy();
        compoundnbt.putString("id", type.toString());
        if (EntityType.getKey(EntityType.LIGHTNING_BOLT).equals(type)) {
           LightningBoltEntity lightningboltentity = new LightningBoltEntity(source.getWorld(), pos.x, pos.y, pos.z, false);
           source.getWorld().addLightningBolt(lightningboltentity);
           source.sendFeedback(new TranslationTextComponent("commands.summon.success", lightningboltentity.getDisplayName()), true);
           return 1;
        } else {
           ServerWorld serverworld = source.getWorld();
           Entity entity = EntityType.loadEntityAndExecute(compoundnbt, serverworld, (p_218914_2_) -> {
              p_218914_2_.setLocationAndAngles(pos.x, pos.y, pos.z, p_218914_2_.rotationYaw, p_218914_2_.rotationPitch);
              return !serverworld.summonEntity(p_218914_2_) ? null : p_218914_2_;
           });
           if (entity == null) {
              throw SUMMON_FAILED.create();
           } else {
              if (randomizeProperties && entity instanceof MobEntity) {
                 ((MobEntity)entity).onInitialSpawn(source.getWorld(), source.getWorld().getDifficultyForLocation(new BlockPos(entity)), SpawnReason.COMMAND, (ILivingEntityData)null, (CompoundNBT)null);
              }
              if(entity instanceof CreatureEntity) {
                  MeanGoose.setAI((CreatureEntity) entity, PlayerEntity.class);
              }
              source.sendFeedback(new TranslationTextComponent("commands.summon.success", entity.getDisplayName()), true);
              return 1;
           }
        }
     }

    public static void setAI(CreatureEntity el, Class<? extends LivingEntity> targetClass) {
        el.targetSelector.goals.clear();
        el.targetSelector.addGoal(0, new AggressiveTargetingGoal<>(el, targetClass, false));
        el.targetSelector.addGoal(1, new HurtByTargetGoal(el));
        Set<Goal> goals = ImmutableSet.copyOf(el.goalSelector.goals);
        for(Goal goal : goals) {
            if(!(goal instanceof EntityGoose.FindItemsGoal)) {
                el.goalSelector.removeGoal(goal);
            }
        }
        el.goalSelector.addGoal(0, new MeleeAttackGoal(el, 1.2D, true) {
            @Override
            protected void checkAndPerformAttack(LivingEntity enemy, double distToEnemySqr) {
                double d0 = this.getAttackReachSqr(enemy);
                if(distToEnemySqr <= d0 && this.attackTick <= 0) {
                    this.attackTick = 20;
                    this.attacker.swingArm(Hand.MAIN_HAND);
                    enemy.attackEntityFrom(DamageSource.causeMobDamage(this.attacker), (float) this.attacker.getAttribute(SharedMonsterAttributes.ATTACK_DAMAGE).getValue());
                }
            }
        });
    }

    public static class AggressiveTargetingGoal<T extends LivingEntity> extends TargetGoal {

        protected final Class<T> targetClass;
        protected LivingEntity nearestTarget;
        protected final EntityPredicate targetEntitySelector;

        public AggressiveTargetingGoal(MobEntity entity, Class<T> targetClass, boolean checkSight) {
            super(entity, checkSight);
            this.targetClass = targetClass;
            this.setMutexFlags(EnumSet.of(Flag.TARGET));
            this.targetEntitySelector = (new EntityPredicate()).setDistance(this.getTargetDistance());
        }

        @Override
        public boolean shouldExecute() {
            this.nearestTarget = null;
            if (this.targetClass != PlayerEntity.class && this.targetClass != ServerPlayerEntity.class) {
                this.nearestTarget = this.goalOwner.world.func_225318_b(this.targetClass, this.targetEntitySelector, this.goalOwner, this.goalOwner.getPosX(), this.goalOwner.getPosYEye(), this.goalOwner.getPosZ(), this.getTargetableArea(this.getTargetDistance()));
            } else {
                this.nearestTarget = this.goalOwner.world.getClosestPlayer(this.targetEntitySelector, this.goalOwner, this.goalOwner.getPosX(), this.goalOwner.getPosYEye(), this.goalOwner.getPosZ());
            }
            return this.nearestTarget != null;
        }

        protected AxisAlignedBB getTargetableArea(double targetDistance) {
            return this.goalOwner.getBoundingBox().grow(targetDistance, 4.0D, targetDistance);
        }

        @Override
        public void startExecuting() {
            this.goalOwner.setAttackTarget(this.nearestTarget);
            super.startExecuting();
        }
    }
}
