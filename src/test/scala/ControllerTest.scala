package TriDiagMatDet

import chisel3._
import chisel3.util._
import chiseltest._
import org.chipsalliance.cde.config._
import freechips.rocketchip.tile._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import org.scalatest.flatspec.AnyFlatSpec
import scala.util.Random
import chisel3.simulator.scalatest.ChiselSim

class TriDiagControllerTest extends AnyFlatSpec with ChiselSim {

  implicit val p: Parameters = Parameters.empty

  behavior of "TriDiagController"

  it should "handle a basic data load and computation flow" in {
    test(new TriDiagController(addrBits = 32, beatBytes = 4)).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      // --- Helper defaults ---
      def resetInputs(): Unit = {
        c.io.dcplrIO.a_valid.poke(false.B)
        c.io.dcplrIO.b_valid.poke(false.B)
        c.io.dcplrIO.c_valid.poke(false.B)
        c.io.dcplrIO.start_valid.poke(false.B)
        c.io.dmem.readRespQueue.valid.poke(false.B)
        c.io.triDiagCoreIO.done.poke(false.B)
        c.io.dmem.readReq.ready.poke(true.B)
        c.io.dmem.writeReq.ready.poke(true.B)
      }

      resetInputs()
      c.io.reset.poke(true.B)
      c.clock.step()
      c.io.reset.poke(false.B)

      // --- Simulate loading A ---
      c.io.dcplrIO.a_valid.poke(true.B)
      c.io.dcplrIO.a_addr.poke(0x1000.U)
      c.clock.step()
      c.io.dcplrIO.a_valid.poke(false.B)

      // Controller should issue DMA read request for A
      c.io.dmem.readReq.valid.expect(true.B)
      c.io.dmem.readReq.bits.addr.expect(0x1000.U)
      c.io.dmem.readReq.bits.totalBytes.expect(32.U) // 256 bits
      c.clock.step()

      // Send 256-bit data via DMA input buffer (simulate 8 x 32-bit chunks)
      for (i <- 0 until 8) {
        while (!c.io.dmem.readRespQueue.ready.peek().litToBoolean) {
          c.clock.step()
        }
        c.io.dmem.readRespQueue.valid.poke(true.B)
        c.io.dmem.readRespQueue.bits.poke((i + 1).U)
        c.clock.step()
      }
      c.io.dmem.readRespQueue.valid.poke(false.B)

      // Should transition back to idle after read into A
      c.clock.step(3)

      // --- Simulate loading B ---
      c.io.dcplrIO.b_valid.poke(true.B)
      c.io.dcplrIO.b_addr.poke(0x2000.U)
      c.clock.step()
      c.io.dcplrIO.b_valid.poke(false.B)

      // Accept another 256 bits for B
      for (i <- 0 until 8) {
        while (!c.io.dmem.readRespQueue.ready.peek().litToBoolean) {
          c.clock.step()
        }
        c.io.dmem.readRespQueue.valid.poke(true.B)
        c.io.dmem.readRespQueue.bits.poke((i + 11).U) // Distinct from A
        c.clock.step()
      }
      c.io.dmem.readRespQueue.valid.poke(false.B)
      c.clock.step(2)

      // --- Simulate loading C ---
      c.io.dcplrIO.c_valid.poke(true.B)
      c.io.dcplrIO.c_addr.poke(0x3000.U)
      c.clock.step()
      c.io.dcplrIO.c_valid.poke(false.B)

      for (i <- 0 until 8) {
        while (!c.io.dmem.readRespQueue.ready.peek().litToBoolean) {
          c.clock.step()
        }
        c.io.dmem.readRespQueue.valid.poke(true.B)
        c.io.dmem.readRespQueue.bits.poke((i + 21).U)
        c.clock.step()
      }
      c.io.dmem.readRespQueue.valid.poke(false.B)

      c.clock.step(5)

      // --- Start computation ---
      c.io.dcplrIO.start_valid.poke(true.B)
      c.io.dcplrIO.result_addr.poke(0xdeadbeefL.U)
      c.clock.step()
      c.io.dcplrIO.start_valid.poke(false.B)

      // TriDiag Core should be started
      c.io.triDiagCoreIO.start.expect(true.B)

      // Simulate core completing in 1 cycle
      c.io.triDiagCoreIO.done.poke(true.B)
      c.clock.step()
      c.io.triDiagCoreIO.done.poke(false.B)

      // Write result to memory
      c.io.dmem.writeReq.valid.expect(true.B)
      c.io.dmem.writeReq.bits.addr.expect(0xdeadbeefL.U)

      c.clock.step()
      c.io.dmem.writeReq.valid.expect(false.B)

      // Should return to idle state and set interrupt
      c.io.dcplrIO.status.expect(ControllerStatus.Idle)
      c.io.dcplrIO.interrupt.expect(true.B)
    }
  }
}
