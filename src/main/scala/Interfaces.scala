package TriDiagMatDet

import chisel3._
import chisel3.util.Decoupled
import org.chipsalliance.cde.config.Parameters

class TriDiagDetCoreIO extends Bundle { 
    val clk        = Input(Clock())
    val rst        = Input(Bool())
    val start      = Input(Bool())
    val ack        = Input(Bool())
    val a_flat     = Input(UInt(240.W))
    val b_flat     = Input(UInt(256.W))
    val c_flat     = Input(UInt(240.W))
    val det        = Output(SInt(32.W))
    val done       = Output(Bool())
}

class DecouplerControllerIO extends Bundle {
  // Exception handling
  val excp_ready  = Output(Bool())
  val excp_valid  = Input(Bool())
  val interrupt   = Output(Bool())
  val busy        = Output(Bool())

  // A array interface
  val a_ready     = Output(Bool())
  val a_valid     = Input(Bool())
  val a_addr      = Input(UInt(32.W))

  // B array interface
  val b_ready     = Output(Bool())
  val b_valid     = Input(Bool())
  val b_addr      = Input(UInt(32.W))

  // C array interface
  val c_ready     = Output(Bool())
  val c_valid     = Input(Bool())
  val c_addr      = Input(UInt(32.W))

  // Start computation
  val start_ready = Output(Bool())
  val start_valid = Input(Bool())
  val result_addr = Input(UInt(32.W))

  // Status Interface
  val status = Output(UInt(3.W)) // 3 bits for Idle, Loaded_A, Loaded_C, Loaded_B, WaitingforStart, Running, Done
}

class ControllerDMAIO (addrBits: Int, beatBytes: Int)(implicit p: Parameters) extends Bundle {
  val writeReq       = Decoupled(new DMAWriterReq(addrBits, beatBytes))
  val readReq        = Decoupled(new DMAReaderReq(addrBits, 256)) // 256 is the max read bits
  val readResp       = Flipped(Decoupled(new DMAReaderResp(256)))
  val readRespQueue  = Flipped(Decoupled(UInt((beatBytes * 8).W)))
  val busy           = Input(Bool())
}