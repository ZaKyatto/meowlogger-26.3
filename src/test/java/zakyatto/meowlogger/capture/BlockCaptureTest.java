package zakyatto.meowlogger.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockCaptureTest {
	private static final BlockPos POS = new BlockPos(5, 64, 5);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void findsTheOtherHalfOfTwoBlockBlocks() {
		assertEquals(POS.above(), BlockCapture.companion(Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), POS));
		assertEquals(POS.below(), BlockCapture.companion(Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), POS));
		assertEquals(POS.above(), BlockCapture.companion(Blocks.SUNFLOWER.defaultBlockState(), POS), "tall plants use the same property");

		var bed = Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
		assertEquals(POS.east(), BlockCapture.companion(bed.setValue(BedBlock.PART, BedPart.FOOT), POS), "the head is where the bed faces");
		assertEquals(POS.west(), BlockCapture.companion(bed.setValue(BedBlock.PART, BedPart.HEAD), POS));

		assertNull(BlockCapture.companion(Blocks.STONE.defaultBlockState(), POS));
		assertNull(BlockCapture.companion(Blocks.OAK_STAIRS.defaultBlockState(), POS), "stairs have a half, but not a two-block one");
	}
}
