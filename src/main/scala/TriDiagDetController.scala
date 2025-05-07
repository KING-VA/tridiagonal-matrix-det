package tridiagonal-matrix-det

import chisel3._
import chisel3.util._
import chipsalliance.rocketchip.config.Parameters

class TriDiagControllerIO(addrBits: Int, beatBytes: Int)(implicit p: Parameters) extends Bundle {
  // System
  val reset = Input(Bool())

  // RoCC Decoupler
  val dcplrIO = new DecouplerControllerIO
  val dmem = new ControllerDMAIO(addrBits, beatBytes)

  // TriDiag Core
  val triDiagCoreIO = Flipped(new TriDiagDetCoreIO)
}

class TriDiagController(addrBits: Int, beatBytes: Int)(implicit p: Parameters) extends Module {
  val io = IO(new TriDiagControllerIO(addrBits, beatBytes))
  
  // Set signals for the TriDiag Core
  io.triDiagCoreIO.rst := io.reset
  io.triDiagCoreIO.clk := clock
  io.triDiagCoreIO.start := false.B // Start signal is set in the controller
  io.triDiagCoreIO.a_flat := a_flat_reg
  io.triDiagCoreIO.b_flat := b_flat_reg
  io.triDiagCoreIO.c_flat := c_flat_reg

  // TODO: Need to update the busy signal if the ROCC is busy
  io.dcplrIO.busy := (cState =/= CtrlState.sIdle) | (mState =/= MemState.sIdle)

  // Internal Registers
  val a_flat_reg = RegInit(0.U(240.W))
  val b_flat_reg = RegInit(0.U(256.W))
  val c_flat_reg = RegInit(0.U(240.W))

  val a_addr_reg = RegInit(0.U(32.W))
  val b_addr_reg = RegInit(0.U(32.W))
  val c_addr_reg = RegInit(0.U(32.W))
  val result_addr_reg = RegInit(0.U(32.W))
  val ready_check_reg = RegInit(false.B)
  
  val done_load_a_reg = RegInit(false.B)
  val done_load_b_reg = RegInit(false.B)
  val done_load_c_reg = RegInit(false.B)

  // DMA Read Queue
  val dequeue = Module(new DMAOutputBuffer(beatBytes))
  dequeue.io.dataOut.ready := mState === MemState.sReadIntoAccel
  dequeue.io.dmaInput <> io.dmem.readRespQueue

  // States (C - Controller, M - Memory)
  val cState = RegInit(CtrlState.sIdle)
  val cStateWire = WireDefault(cState)
  val mState = RegInit(MemState.sIdle)
  val mStateWire = WireDefault(mState)

  // Helper Wires
  val addrWire = Wire(UInt(32.W))
  val data_wr_done = mState === MemState.sIdle
  val data_ld_done = mState === MemState.sIdle
  val reversedData = Wire(UInt(32.W))
  
  // Default DecouplerIO Signals
  io.dcplrIO.a_ready := false.B
  io.dcplrIO.b_ready := false.B
  io.dcplrIO.c_ready := false.B
  io.dcplrIO.start_ready := false.B
  io.dcplrIO.excp_ready := true.B
  io.dcplrIO.interrupt := false.B
  io.dcplrIO.status := ControllerStatus.Idle

  // Default DMA readReq Values
  io.dmem.readReq.valid := false.B
  io.dmem.readReq.bits.addr := 0.U
  io.dmem.readReq.bits.totalBytes := 0.U
  io.dmem.readResp.ready := false.B

  // Address Mapping
  when(cState === CtrlState.sASetup) {
    addrWire := a_addr_reg
  }.elsewhen(cState === CtrlState.sBSetup) {
    addrWire := b_addr_reg
  }.elsewhen(cState === CtrlState.sCSetup) {
    addrWire := c_addr_reg
  }.otherwise {
    addrWire := 0.U
  }

  // Update states
  when(io.reset | io.dcplrIO.excp_valid) {
    cState := CtrlState.sIdle
    mState := MemState.sIdle
  }.otherwise {
    cState := cStateWire
    mState := mStateWire
  }

  // Controller FSM
  switch(cState) {
    is(CtrlState.sIdle) {
      io.dcplrIO.a_ready := true.B
      io.dcplrIO.b_ready := true.B
      io.dcplrIO.c_ready := true.B
      io.dcplrIO.start_ready := done_load_a_reg && done_load_b_reg && done_load_c_reg
      io.dcplrIO.status := Mux(done_load_a_reg && done_load_b_reg && done_load_c_reg, ControllerStatus.WaitingforStart, ControllerStatus.Idle)

      io.dcplrIO.interrupt := false.B // Reset the interrupt signal
      io.triDiagCoreIO.ack := false.B // Reset the ack signal

      when(io.dcplrIO.a_valid) {
        a_addr_reg := io.dcplrIO.a_addr
        mStateWire := MemState.sReadReq
        cStateWire := CtrlState.sASetup
      }.elsewhen(io.dcplrIO.b_valid) {
        b_addr_reg := io.dcplrIO.b_addr
        mStateWire := MemState.sReadReq
        cStateWire := CtrlState.sBSetup
      }.elsewhen(io.dcplrIO.c_valid) {
        c_addr_reg := io.dcplrIO.c_addr
        mStateWire := MemState.sReadReq
        cStateWire := CtrlState.sCSetup
      }.elsewhen(io.dcplrIO.start_valid && io.dcplrIO.start_ready) {
        result_addr_reg := io.dcplrIO.result_addr
        io.triDiagCoreIO.start := true.B // Start the computation
        cStateWire := CtrlState.sRun
      }
    }
    is(CtrlState.sASetup) {
      when(data_ld_done) {
        done_load_a_reg := true.B
        cStateWire := CtrlState.sIdle
      }
    }
    is(CtrlState.sBSetup) {
      when(data_ld_done) {
        done_load_b_reg := true.B
        cStateWire := CtrlState.sIdle
      }
    }
    is(CtrlState.sCSetup) {
      when(data_ld_done) {
        done_load_c_reg := true.B
        cStateWire := CtrlState.sIdle
      }
    }
    is(CtrlState.sRun) {
      io.triDiagCoreIO.start := true.B // Start the computation
      io.dcplrIO.status := ControllerStatus.Running // Set the status to running
      when(io.triDiagCoreIO.done) {
        io.triDiagCoreIO.start := false.B // Stop the computation
        cStateWire := CtrlState.sDataWrite // Move to data write state
        mStateWire := MemState.sWriteReq // Move to memory write state
      }
    }
    is(CtrlState.sDataWrite) {
      io.dcplrIO.status := ControllerStatus.Done // Set the status to done
      when(data_wr_done) {
        io.triDiagCoreIO.ack := true.B // Acknowledge the core
        cStateWire := CtrlState.sIdle
        done_load_a_reg := false.B // Reset the load registers
        done_load_b_reg := false.B
        done_load_c_reg := false.B
        io.dcplrIO.interrupt := true.B // Set the interrupt to true when done
      }
    }
  }

  // Memory FSM
  switch(mState) {
    is(MemState.sIdle) {
      io.dmem.readReq.valid := false.B
      io.dmem.writeReq.valid := false.B

    }
    is(MemState.sReadReq) {
      io.dmem.readReq.valid := true.B
      io.dmem.readReq.bits.addr := addrWire
      io.dmem.readReq.bits.totalBytes := (256 / 8).U
      when(io.dmem.readReq.fire()) {
        mStateWire := MemState.sReadIntoAccel
      }
    }
    is(MemState.sReadIntoAccel) {
      when (dequeue.io.dataOut.fire()) { // When we dequeue, read into the flat_regs
        // Depending on size and state, we will load the data into the flat_regs
        when(cState === CtrlState.sASetup) {
          a_flat_reg := dequeue.io.dataOut.bits(239, 0) // Load the A array with 240 bits
        }.elsewhen(cState === CtrlState.sBSetup) {
          b_flat_reg := dequeue.io.dataOut.bits(255, 0) // Load the B array with 256 bits
        }.elsewhen(cState === CtrlState.sCSetup) {
          c_flat_reg := dequeue.io.dataOut.bits(239, 0) // Load the C array with 240 bits
        }
        mStateWire := MemState.sIdle // Set the state back to idle
    }
    is(MemState.sWriteReq) {
      io.dmem.writeReq.bits.addr := result_addr_reg
      io.dmem.writeReq.bits.totalBytes := beatBytes
      // Take the results from the TriDiag Core and reverse them for little endian
      reversedData := Cat(io.triDiagCoreIO.det(7, 0), io.triDiagCoreIO.det(15, 8), io.triDiagCoreIO.det(23, 16), io.triDiagCoreIO.det(31, 24))
      io.dmem.writeReq.bits.data := Cat(0.U((beatBytes*8-32).W), reversedData) // Write the data to the result address
      io.dmem.writeReq.valid := true.B
      when(io.dmem.writeReq.fire()) {
        mStateWire := MemState.sIdle
      }     
    }
  }
}