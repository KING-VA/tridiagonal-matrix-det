package TriDiagMatDet

import chisel3._
import chisel3.util._
import chiseltest._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tile._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.simulator.scalatest.ChiselSim

class DMAOutputBufferTest extends AnyFlatSpec with ChiselSim {

  behavior of "DMAOutputBuffer"

  it should "accumulate input beats and output 256-bit reversed data" in {
    test(new DMAOutputBuffer(beatBytes = 4)).withAnnotations(Seq(VcsBackendAnnotation, WriteFsdbAnnotation)) { c =>
      // Helper to convert a sequence of UInts into a full 256-bit BigInt (LSB first)
      def buildFullWord(beats: Seq[BigInt]): BigInt = {
        beats.zipWithIndex.map { case (b, i) => b << (32 * i) }.reduce(_ | _)
      }

      // Test values (8 beats of 4 bytes = 256 bits)
      val beatData = Seq.tabulate(8)(i => BigInt(i + 1)) // e.g. 0x01, 0x02, ..., 0x08

      // Push all beats
      for (data <- beatData) {
        while (!c.io.dmaInput.ready.peek().litToBoolean) {
          c.clock.step()
        }
        c.io.dmaInput.valid.poke(true.B)
        c.io.dmaInput.bits.poke(data.U)
        c.clock.step()
        c.io.dmaInput.valid.poke(false.B)
      }

      // Wait until dataOut is valid
      while (!c.io.dataOut.valid.peek().litToBoolean) {
        c.clock.step()
      }

      // Expected full word
      val fullWord = buildFullWord(beatData)
      val expectedReversed = fullWord.toByteArray.reverse.padTo(32, 0.toByte).reverse
      val expectedBigInt = BigInt(expectedReversed)

      c.io.dataOut.ready.poke(true.B)
      c.io.dataOut.bits.expect(expectedBigInt.U)
      c.clock.step()

      // After consuming the output, ensure internal state is reset
      c.io.dataOut.valid.expect(false.B)
      c.io.dmaInput.ready.expect(true.B)
    }
  }
}