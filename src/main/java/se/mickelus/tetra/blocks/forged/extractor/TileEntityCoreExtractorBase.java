package se.mickelus.tetra.blocks.forged.extractor;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraft.world.WorldServer;
import se.mickelus.tetra.blocks.IHeatTransfer;
import se.mickelus.tetra.util.TileEntityOptional;

import javax.annotation.Nullable;
import java.util.Optional;

public class TileEntityCoreExtractorBase extends TileEntity implements ITickable, IHeatTransfer {

    private boolean isSending = false;

    private static final int sendLimit = 16;

    private static final int maxCharge = 128;
    private int currentCharge = 0;
    private float efficiency;

    public TileEntityCoreExtractorBase() {
    }

    public boolean canRefill() {
        return getPiston()
                .map(te -> !te.isActive())
                .orElse(false);
    }

    @Override
    public void setReceiving(boolean receiving) {
        if (receiving) {
            isSending = false;
        }
    }

    @Override
    public boolean isReceiving() {
        return false;
    }

    @Override
    public boolean canRecieve() {
        return false;
    }

    @Override
    public boolean canSend() {
        return currentCharge > 0;
    }


    @Override
    public void setSending(boolean sending) {
        isSending = sending;
    }

    @Override
    public boolean isSending() {
        return isSending;
    }

    @Override
    public int getReceiveLimit() {
        return 0;
    }

    @Override
    public int getSendLimit() {
        return sendLimit;
    }

    @Override
    public int drain(int amount) {
        if (amount > currentCharge) {
            int drained = currentCharge;
            currentCharge = 0;
            return drained;
        }

        currentCharge -= amount;
        return amount;
    }

    @Override
    public int fill(int amount) {
        if (amount + currentCharge > maxCharge) {
            int overfill = amount + currentCharge - maxCharge;
            currentCharge = maxCharge;
            return overfill;
        }

        currentCharge += amount;

        updateTransferState();

        return 0;
    }

    @Override
    public int getCharge() {
        return currentCharge;
    }

    @Override
    public float getEfficiency() {
        return 1;
    }

    @Override
    public void update() {
        if (isSending) {
            if (world.getTotalWorldTime() % 5 == 0) {
                transfer();
            }
        }
    }

    @Override
    public void updateTransferState() {
        getConnectedUnit().ifPresent(connected -> {
            boolean canTransfer = canSend() && connected.canRecieve();
            setSending(canTransfer);
            connected.setReceiving(canTransfer);

            efficiency = getEfficiency() * connected.getEfficiency();
        });
        markDirty();
    }


    public void transfer() {
        getConnectedUnit()
                .ifPresent(connected -> {
                    if (connected.canRecieve()) {
                        if (canSend()) {
                            int amount = drain(Math.min(getSendLimit(), connected.getReceiveLimit()));
                            int overfill = connected.fill((int) (amount * efficiency));

                            if (overfill > 0) {
                                fill(overfill);
                            }

                            markDirty();
                        } else {
                            if (canRefill()) {
                                getPiston().ifPresent(TileEntityCoreExtractorPiston::activate);
                            }

                            setSending(false);
                            connected.setReceiving(false);

                            runFilledEffects();

                            notifyBlockUpdate();
                        }
                    } else {
                        setSending(false);
                        connected.setReceiving(false);

                        runFilledEffects();

                        notifyBlockUpdate();
                    }
                });

    }

    private void runFilledEffects() {
        if (world instanceof WorldServer) {
            ((WorldServer) world).spawnParticle(EnumParticleTypes.SMOKE_NORMAL,
                    pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5,
                    10,  0, 0, 0, 0.02f);
            world.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS,
                    0.2f, 1);
        }
    }

    private void notifyBlockUpdate() {
        markDirty();
        IBlockState state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state,3);
    }

    public EnumFacing getFacing() {
        return world.getBlockState(pos).getValue(BlockCoreExtractorBase.propFacing);
    }

    private Optional<IHeatTransfer> getConnectedUnit() {
        return TileEntityOptional.from(world, pos.offset(getFacing()), IHeatTransfer.class);
    }

    private Optional<TileEntityCoreExtractorPiston> getPiston() {
        return TileEntityOptional.from(world, pos.offset(EnumFacing.UP), TileEntityCoreExtractorPiston.class);
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);

        if (compound.hasKey("charge")) {
            currentCharge = compound.getInteger("charge");
        } else {
            currentCharge = 0;
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);

        compound.setInteger("charge", currentCharge);

        return compound;
    }

    @Nullable
    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        this.readFromNBT(packet.getNbtCompound());
        IBlockState state = world.getBlockState(pos);

        updateTransferState();
        world.notifyBlockUpdate(pos, state, state,3);
    }
}
