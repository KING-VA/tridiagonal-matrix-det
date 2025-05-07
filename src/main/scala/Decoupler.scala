package tridiagonal-matrix-det

import chisel3._
import chisel3.util.Decoupled
import chipsalliance.rocketchip.config.Parameters
import freechips.rocketchip.tile.RoCCCommand
import freechips.rocketchip.tile.RoCCResponse

class TriDiagDecouplerIO(implicit p: Parameters) extends Bundle {
  // System Signal
  val reset     = Input(Bool())

  // RoCCCommand + Other Signals
  val rocc_cmd  = Flipped(Decoupled(new RoCCCommand))
  val rocc_resp = Decoupled(new RoCCResponse)
  val rocc_busy = Output(Bool())
  val rocc_intr = Output(Bool())
  val rocc_excp = Input(Bool())

  // Controller
  val ctrlIO  = Flipped(new DecouplerControllerIO)
}

class TriDiagDecoupler(implicit p: Parameters) extends Module {
  // Internal Registers
  val excp_valid_reg  = RegInit(false.B)
  val a_valid_reg     = RegInit(false.B)
  val b_valid_reg     = RegInit(false.B)
  val c_valid_reg     = RegInit(false.B)
  val a_addr_reg      = RegInit(0.U(32.W))
  val b_addr_reg      = RegInit(0.U(32.W))
  val c_addr_reg      = RegInit(0.U(32.W))
  val start_valid_reg = RegInit(false.B)
  val result_addr_reg = RegInit(0.U(32.W))
  val resp_rd_reg     = RegInit(0.U(5.W))
  val resp_data_reg   = RegInit(0.U(32.W))
  val resp_valid_reg  = RegInit(false.B)

  // Helper wires
  val reset_wire = Wire(Bool())
  val funct      = Wire(UInt(7.W))
  val rs1_data   = Wire(UInt(32.W))
  val rs2_data   = Wire(UInt(32.W))
  val rd         = Wire(UInt(5.W))
  val busy       = Wire(Bool())

  // IO
  val io = IO(new TriDiagDecouplerIO)

  // Unwrapping RoCCCommands
  when(io.rocc_cmd.fire & ~reset_wire) {
    switch(funct) {
      is(TriDiagISA.READIN_A) {
        a_valid_reg := true.B
        a_addr_reg  := rs1_data
      }
      is(TriDiagISA.READIN_B) {
        b_valid_reg := true.B
        b_addr_reg  := rs1_data
      }
      is(TriDiagISA.READIN_C) {
        c_valid_reg := true.B
        c_addr_reg  := rs1_data
      }
      is(TriDiagISA.START_COMP) {
        start_valid_reg := true.B
        result_addr_reg := rs1_data
      }
      is(TriDiagISA.QUERYSTATUS) {
        resp_rd_reg    := rd
        // Set response data to be {padding, busy, status from controller}
        resp_data_reg  := Cat(0.U(28.W), busy, io.ctrlIO.status)
        resp_valid_reg := true.B
      }
    }
  }

  // When an exception is received (only ignored when io.reset is high)
  when(io.rocc_excp & ~io.reset) {
    excp_valid_reg := true.B
  }

  // When register groups "fire" (ready && valid high)
  when((io.ctrlIO.a_ready & io.ctrlIO.a_valid) | reset_wire) {
    a_valid_reg := false.B
  }
  when((io.ctrlIO.b_ready & io.ctrlIO.b_valid) | reset_wire) {
    b_valid_reg := false.B
  }
  when((io.ctrlIO.c_ready & io.ctrlIO.c_valid) | reset_wire) {
    c_valid_reg := false.B
  }
  when((io.ctrlIO.start_ready & io.ctrlIO.start_valid) | reset_wire) {
    start_valid_reg := false.B
  }
  when((io.ctrlIO.excp_ready & io.ctrlIO.excp_valid) | io.reset) {
    excp_valid_reg := false.B
  }

  // When response fires
  when(io.rocc_resp.fire | reset_wire) {
    resp_valid_reg := false.B
  }

  // Assigning other wires/signals
  io.ctrlIO.excp_valid  := excp_valid_reg
  io.ctrlIO.a_valid     := a_valid_reg
  io.ctrlIO.a_addr      := a_addr_reg
  io.ctrlIO.b_valid     := b_valid_reg
  io.ctrlIO.b_addr      := b_addr_reg
  io.ctrlIO.c_valid     := c_valid_reg
  io.ctrlIO.c_addr      := c_addr_reg
  io.ctrlIO.start_valid := start_valid_reg
  io.ctrlIO.result_addr := result_addr_reg

  reset_wire    := io.rocc_excp | io.reset
  funct         := io.rocc_cmd.bits.inst.funct
  rs1_data      := io.rocc_cmd.bits.rs1
  rs2_data      := io.rocc_cmd.bits.rs2
  rd            := io.rocc_cmd.bits.inst.rd
  busy          := (start_valid_reg | io.ctrlIO.busy)

  // Should be always ready to process instructions
  io.rocc_cmd.ready      := true.B
  io.rocc_resp.valid     := resp_valid_reg
  io.rocc_resp.bits.rd   := resp_rd_reg
  io.rocc_resp.bits.data := resp_data_reg
  io.rocc_busy           := busy
  io.rocc_intr           := io.ctrlIO.interrupt
}