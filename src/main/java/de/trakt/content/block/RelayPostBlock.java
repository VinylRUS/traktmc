package de.trakt.content.block;

import de.trakt.relay.RelayService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * F3b relay terminal: a courier post players deposit goods at and pick deliveries
 * up from. Single block, no block entity — shipment state lives in the relay
 * SavedData, the registry binds the block position to its owner.
 */
public final class RelayPostBlock extends HorizontalDirectionalBlock {
    private static final VoxelShape NORTH_SOUTH = Shapes.or(
            Block.box(6, 0, 6, 10, 12, 10),
            Block.box(5, 12, 5, 11, 16, 11));
    private static final VoxelShape EAST_WEST = Shapes.or(
            Block.box(6, 0, 6, 10, 12, 10),
            Block.box(5, 12, 5, 11, 16, 11));

    public RelayPostBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING) != null && state.getValue(FACING).getAxis() == Direction.Axis.X
                ? EAST_WEST : NORTH_SOUTH;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (level instanceof ServerLevel world && placer instanceof ServerPlayer player) {
            RelayService.onTerminalPlaced(world, player, pos);
        }
    }

    @Override
    public void destroy(net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel world) {
            RelayService.onTerminalRemoved(world, pos);
        }
        super.destroy(level, pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel world && player instanceof ServerPlayer serverPlayer) {
            return RelayService.useTerminal(world, serverPlayer, pos);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (level instanceof ServerLevel world && player instanceof ServerPlayer serverPlayer) {
            if (player.isShiftKeyDown() && !stack.isEmpty()) {
                return RelayService.depositToDraft(world, serverPlayer, pos, hand);
            }
            return RelayService.useTerminal(world, serverPlayer, pos);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
}
