/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.blocks.metal;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.crafting.MultiblockRecipe;
import blusunrize.immersiveengineering.api.excavator.ExcavatorHandler;
import blusunrize.immersiveengineering.api.excavator.MineralMix;
import blusunrize.immersiveengineering.api.excavator.MineralVein;
import blusunrize.immersiveengineering.api.excavator.MineralWorldInfo;
import blusunrize.immersiveengineering.api.utils.CapabilityReference;
import blusunrize.immersiveengineering.api.utils.DirectionalBlockPos;
import blusunrize.immersiveengineering.api.utils.SafeChunkUtils;
import blusunrize.immersiveengineering.api.utils.shapes.CachedShapesWithTransform;
import blusunrize.immersiveengineering.common.IETileTypes;
import blusunrize.immersiveengineering.common.blocks.IEBlockInterfaces.IBlockBounds;
import blusunrize.immersiveengineering.common.blocks.generic.PoweredMultiblockTileEntity;
import blusunrize.immersiveengineering.common.blocks.multiblocks.IEMultiblocks;
import blusunrize.immersiveengineering.common.config.IEServerConfig;
import blusunrize.immersiveengineering.common.network.MessageTileSync;
import blusunrize.immersiveengineering.common.util.FakePlayerUtil;
import blusunrize.immersiveengineering.common.util.Utils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootContext;
import net.minecraft.loot.LootContext.Builder;
import net.minecraft.loot.LootParameters;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

public class ExcavatorTileEntity extends PoweredMultiblockTileEntity<ExcavatorTileEntity, MultiblockRecipe> implements
		IBlockBounds
{
	private static final BlockPos wheelCenterOffset = new BlockPos(1, 1, 1);
	public boolean active = false;

	public ExcavatorTileEntity()
	{
		super(IEMultiblocks.EXCAVATOR, 64000, true, IETileTypes.EXCAVATOR.get());
	}


	@Override
	public void readCustomNBT(CompoundNBT nbt, boolean descPacket)
	{
		super.readCustomNBT(nbt, descPacket);
	}

	@Override
	public void writeCustomNBT(CompoundNBT nbt, boolean descPacket)
	{
		super.writeCustomNBT(nbt, descPacket);
	}

	public BlockPos getWheelCenterPos()
	{
		return this.getBlockPosForPos(wheelCenterOffset);
	}

	@Override
	protected int getComparatorValueOnMaster()
	{
		BlockPos wheelPos = getWheelCenterPos();
		if(SafeChunkUtils.getSafeTE(world, wheelPos) instanceof BucketWheelTileEntity)
		{
			MineralWorldInfo info = ExcavatorHandler.getMineralWorldInfo(world, wheelPos);
			if(info==null)
				return 0;
			if(ExcavatorHandler.mineralVeinYield==0)
				return 15;
			final long[] totalDepletion = {0};
			List<Pair<MineralVein, Integer>> veins = info.getAllVeins();
			veins.forEach(pair -> totalDepletion[0] += pair.getLeft().getDepletion());
			totalDepletion[0] /= veins.size();
			float remain = (ExcavatorHandler.mineralVeinYield-totalDepletion[0])/(float)ExcavatorHandler.mineralVeinYield;
			return MathHelper.floor(Math.max(remain, 0)*15);
		}
		return 0;
	}

	@Override
	public void tick()
	{
		super.tick();
		if(isDummy())
			return;
		BlockPos wheelPos = this.getBlockPosForPos(wheelCenterOffset);
		if(!world.isRemote&&world.isAreaLoaded(wheelPos, 5))
		{
			TileEntity center = world.getTileEntity(wheelPos);

			if(center instanceof BucketWheelTileEntity)
			{
				BucketWheelTileEntity wheel = ((BucketWheelTileEntity)center);
				if(wheel!=wheel.master())
					return;
				float rot = 0;
				int target = -1;
				Direction fRot = this.getFacing().rotateYCCW();
				boolean mirrored = getIsMirrored();

				if((wheel.getFacing()==fRot)&&(wheel.getIsMirrored()==mirrored))
				{
					if(active!=wheel.active)
						world.addBlockEvent(wheel.getPos(), wheel.getBlockState().getBlock(), 0, active?1: 0);
					rot = wheel.rotation;
					if(rot%45 > 40)
						target = Math.round(rot/360f*8)%8;
				}
				else
					wheel.adjustStructureFacingAndMirrored(fRot,mirrored);

				if(!isRSDisabled())
				{
					MineralVein mineralVein = ExcavatorHandler.getRandomMineral(world, wheelPos);
					MineralMix mineral = mineralVein!=null?mineralVein.getMineral(): null;

					int consumed = IEServerConfig.MACHINES.excavator_consumption.get();
					int extracted = energyStorage.extractEnergy(consumed, true);
					if(extracted >= consumed)
					{
						energyStorage.extractEnergy(consumed, false);
						active = true;

						if(target >= 0)
						{
							int targetDown = (target+4)%8;
							CompoundNBT packet = new CompoundNBT();
							if(wheel.digStacks.get(targetDown).isEmpty())
							{
								ItemStack blocking = this.digBlocksInTheWay(wheel);
								if(!blocking.isEmpty())
								{
									wheel.digStacks.set(targetDown, blocking);
									wheel.markDirty();
									this.markContainingBlockForUpdate(null);
								}
								else if(mineral!=null)
								{
									// Extracted to a method, to allow for early exiting
									fillBucket(mineralVein, mineral, wheelPos, wheel, targetDown);
									mineralVein.deplete();
								}
								if(!wheel.digStacks.get(targetDown).isEmpty())
								{
									packet.putInt("fill", targetDown);
									packet.put("fillStack", wheel.digStacks.get(targetDown).write(new CompoundNBT()));
								}
							}
							if(!wheel.digStacks.get(target).isEmpty())
							{
								this.doProcessOutput(wheel.digStacks.get(target).copy());
								Block b = Block.getBlockFromItem(wheel.digStacks.get(target).getItem());
								if(b!=Blocks.AIR)
									wheel.particleStack = wheel.digStacks.get(target).copy();
								wheel.digStacks.set(target, ItemStack.EMPTY);
								wheel.markDirty();
								this.markContainingBlockForUpdate(null);
								packet.putInt("empty", target);
							}
							if(!packet.isEmpty())
							{
								packet.putInt("rotation", (int)wheel.rotation);
								ImmersiveEngineering.packetHandler.send(PacketDistributor.TRACKING_CHUNK.with(() -> world.getChunkAt(pos)),
										new MessageTileSync(wheel, packet));
							}
						}
					}
					else if(active)
						active = false;
				}
				else if(active)
				{
					active = false;
				}
			}
		}
	}

	ItemStack digBlocksInTheWay(BucketWheelTileEntity wheel)
	{
		BlockPos pos = wheel.getPos().add(0, -4, 0);
		ItemStack s = digBlock(pos);
		if(!s.isEmpty())
			return s;
		//Backward 1
		s = digBlock(pos.offset(getFacing(), -1));
		if(!s.isEmpty())
			return s;
		//Backward 2
		s = digBlock(pos.offset(getFacing(), -2));
		if(!s.isEmpty())
			return s;
		//Forward 1
		s = digBlock(pos.offset(getFacing(), 1));
		if(!s.isEmpty())
			return s;
		//Forward 2
		s = digBlock(pos.offset(getFacing(), 2));
		if(!s.isEmpty())
			return s;

		//Backward+Sides
		s = digBlock(pos.offset(getFacing(), -1).offset(getFacing().rotateY()));
		if(!s.isEmpty())
			return s;
		s = digBlock(pos.offset(getFacing(), -1).offset(getFacing().rotateYCCW()));
		if(!s.isEmpty())
			return s;
		//Center Sides
		s = digBlock(pos.offset(getFacing().rotateY()));
		if(!s.isEmpty())
			return s;
		s = digBlock(pos.offset(getFacing().rotateYCCW()));
		if(!s.isEmpty())
			return s;
		//Forward+Sides
		s = digBlock(pos.offset(getFacing(), 1).offset(getFacing().rotateY()));
		if(!s.isEmpty())
			return s;
		s = digBlock(pos.offset(getFacing(), 1).offset(getFacing().rotateYCCW()));
		if(!s.isEmpty())
			return s;
		return ItemStack.EMPTY;
	}


	ItemStack digBlock(BlockPos pos)
	{
		if(!(world instanceof ServerWorld))
			return ItemStack.EMPTY;
		FakePlayer fakePlayer = FakePlayerUtil.getFakePlayer(world);
		BlockState blockstate = world.getBlockState(pos);
		Block block = blockstate.getBlock();
		if(!world.isAirBlock(pos)&&blockstate.getBlockHardness(world, pos)!=-1)
		{
			if(!block.canHarvestBlock(blockstate, world, pos, fakePlayer))
				return ItemStack.EMPTY;
			block.onBlockHarvested(world, pos, blockstate, fakePlayer);
			if(block.removedByPlayer(blockstate, world, pos, fakePlayer, true, blockstate.getFluidState()))
			{
				block.onPlayerDestroy(world, pos, blockstate);

				ItemStack tool = new ItemStack(Items.IRON_PICKAXE);
				tool.addEnchantment(Enchantments.SILK_TOUCH, 1);
				LootContext.Builder dropContext = new Builder((ServerWorld)world)
						.withNullableParameter(LootParameters.ORIGIN, Vector3d.copyCentered(pos))
						.withNullableParameter(LootParameters.TOOL, tool);

				List<ItemStack> itemsNullable = blockstate.getDrops(dropContext);
				NonNullList<ItemStack> items = NonNullList.create();
				items.addAll(itemsNullable);

				for(int i = 0; i < items.size(); i++)
					if(i!=0)
					{
						ItemEntity ei = new ItemEntity(world, pos.getX()+.5, pos.getY()+.5, pos.getZ()+.5, items.get(i).copy());
						this.world.addEntity(ei);
					}
				world.playEvent(2001, pos, Block.getStateId(blockstate));
				if(items.size() > 0)
					return items.get(0);
			}
		}
		return ItemStack.EMPTY;
	}

	private void fillBucket(MineralVein mineralVein, MineralMix mineralMix, BlockPos wheelPos, BucketWheelTileEntity wheel, int targetDown)
	{
		if(mineralVein.isDepleted())
			return;
		ItemStack ore = mineralMix.getRandomOre(Utils.RAND);
		if(ore.isEmpty())
			return;
		// if random number of 0-1 is smaller than the fail chance of the specific mineral
		if(Utils.RAND.nextFloat() < mineralMix.failChance)
			return;
		// if random number of 0-1 is smaller than the distance based fail chance of the vein
		if(Utils.RAND.nextFloat() < mineralVein.getFailChance(wheelPos))
			return;
		wheel.digStacks.set(targetDown, ore);
		wheel.markDirty();
		this.markContainingBlockForUpdate(null);
	}

	private static final CachedShapesWithTransform<BlockPos, Pair<Direction, Boolean>> SHAPES =
			CachedShapesWithTransform.createForMultiblock(ExcavatorTileEntity::getShape);

	@Override
	public VoxelShape getBlockBounds(@Nullable ISelectionContext ctx)
	{
		return getShape(SHAPES);
	}

	private static List<AxisAlignedBB> getShape(BlockPos posInMultiblock)
	{
		if(posInMultiblock.getX()==2&&posInMultiblock.getZ()==4)
			return ImmutableList.of(
					new AxisAlignedBB(0, 0, 0, .5f, 1, 1),
					new AxisAlignedBB(.5f, .25f, .25f, 1, .75f, .75f)
			);
		else if(posInMultiblock.getZ() < 3&&posInMultiblock.getY()==0&&posInMultiblock.getX()==0)
		{
			List<AxisAlignedBB> list = Lists.newArrayList(new AxisAlignedBB(.5f, 0, 0, 1, 1, 1));
			if(posInMultiblock.getZ()==2)
				list.add(new AxisAlignedBB(0, .5f, 0, .5f, 1, .5f));
			else if(posInMultiblock.getZ()==1)
				list.add(new AxisAlignedBB(0, .5f, 0, .5f, 1, 1));
			else
				list.add(new AxisAlignedBB(0, .5f, .5f, .5f, 1, 1));
			return list;
		}
		else if(new BlockPos(2, 2, 2).equals(posInMultiblock))
			return ImmutableList.of(
					new AxisAlignedBB(0, 0, .375f, 1, 1, .5f),
					new AxisAlignedBB(.875f, 0, 0, 1, 1, .375f)
			);
		else if(new BlockPos(2, 2, 0).equals(posInMultiblock))
			return ImmutableList.of(
					new AxisAlignedBB(0, 0, .5f, 1, 1, .625f),
					new AxisAlignedBB(.875f, 0, .625f, 1, 1, 1)
			);
		final AxisAlignedBB ret;
		if(new BlockPos(0, 2, 2).equals(posInMultiblock))
			ret = new AxisAlignedBB(0, 0, 0, 1, .5f, .5f);
		else if(new BlockPos(0, 2, 1).equals(posInMultiblock))
			ret = new AxisAlignedBB(0, 0, 0, 1, .5f, 1);
		else if(new BlockPos(0, 2, 0).equals(posInMultiblock))
			ret = new AxisAlignedBB(0, 0, .5f, 1, .5f, 1);
		else if(new BlockPos(2, 2, 2).equals(posInMultiblock))
			ret = new AxisAlignedBB(0, 0, .375f, 1, 1, .5f);
		else if(new BlockPos(2, 2, 1).equals(posInMultiblock))
			ret = new AxisAlignedBB(.875f, 0, 0, 1, 1, 1);
		else if(new BlockPos(2, 2, 0).equals(posInMultiblock))
			ret = new AxisAlignedBB(0, 0, .5f, 1, 1, .625f);
		else if(posInMultiblock.getX()==2&&posInMultiblock.getZ()==4)
			ret = new AxisAlignedBB(0, 0, 0, .5f, 1, 1);
		else if(posInMultiblock.getZ() < 3&&posInMultiblock.getY()==0&&posInMultiblock.getX()==0)
			ret = new AxisAlignedBB(.5f, 0, 0, 1, 1, 1);
		else if(posInMultiblock.getZ() < 3&&posInMultiblock.getY()==0&&posInMultiblock.getX()==2)
			ret = new AxisAlignedBB(0, 0, 0, .5f, 1, 1);
		else
			ret = new AxisAlignedBB(0, 0, 0, 1, 1, 1);
		return ImmutableList.of(ret);
	}

	@Override
	public Set<BlockPos> getEnergyPos()
	{
		return ImmutableSet.of(
				new BlockPos(2, 0, 4),
				new BlockPos(2, 1, 4),
				new BlockPos(2, 2, 4)
		);
	}

	@Override
	public Set<BlockPos> getRedstonePos()
	{
		return ImmutableSet.of(
				new BlockPos(0, 1, 5)
		);
	}

	@Override
	public boolean isInWorldProcessingMachine()
	{
		return false;
	}

	@Override
	public boolean additionalCanProcessCheck(MultiblockProcess<MultiblockRecipe> process)
	{
		return false;
	}

	private CapabilityReference<IItemHandler> output = CapabilityReference.forTileEntityAt(this,
			() -> new DirectionalBlockPos(getPos().offset(getFacing(), -1), getFacing().getOpposite()), CapabilityItemHandler.ITEM_HANDLER_CAPABILITY);

	@Override
	public void doProcessOutput(ItemStack output)
	{
		output = Utils.insertStackIntoInventory(this.output, output, false);
		if(!output.isEmpty())
			Utils.dropStackAtPos(world, getPos().offset(getFacing(), -1), output, getFacing());
	}

	@Override
	public void doProcessFluidOutput(FluidStack output)
	{
	}

	@Override
	public void onProcessFinish(MultiblockProcess<MultiblockRecipe> process)
	{
	}

	@Override
	public int getMaxProcessPerTick()
	{
		return 0;
	}

	@Override
	public int getProcessQueueMaxLength()
	{
		return 0;
	}

	@Override
	public float getMinProcessDistance(MultiblockProcess<MultiblockRecipe> process)
	{
		return 0;
	}


	@Override
	public NonNullList<ItemStack> getInventory()
	{
		return null;
	}

	@Override
	public boolean isStackValid(int slot, ItemStack stack)
	{
		return false;
	}

	@Override
	public int getSlotLimit(int slot)
	{
		return 0;
	}

	@Override
	public int[] getOutputSlots()
	{
		return new int[0];
	}

	@Override
	public int[] getOutputTanks()
	{
		return new int[0];
	}

	@Override
	public IFluidTank[] getInternalTanks()
	{
		return null;
	}

	@Override
	protected IFluidTank[] getAccessibleFluidTanks(Direction side)
	{
		return new IFluidTank[0];
	}

	@Override
	protected boolean canFillTankFrom(int iTank, Direction side, FluidStack resources)
	{
		return false;
	}

	@Override
	protected boolean canDrainTankFrom(int iTank, Direction side)
	{
		return false;
	}

	@Override
	public void doGraphicalUpdates(int slot)
	{
		this.markDirty();
		this.markContainingBlockForUpdate(null);
	}


	@Override
	public MultiblockRecipe findRecipeForInsertion(ItemStack inserting)
	{
		return null;
	}

	@Override
	protected MultiblockRecipe getRecipeForId(ResourceLocation id)
	{
		return null;
	}

	@Override
	public void disassemble()
	{
		super.disassemble();
		BlockPos wheelPos = this.getBlockPosForPos(wheelCenterOffset);
		TileEntity center = world.getTileEntity(wheelPos);
		if(center instanceof BucketWheelTileEntity)
			world.addBlockEvent(center.getPos(), center.getBlockState().getBlock(), 0, 0);
	}
}