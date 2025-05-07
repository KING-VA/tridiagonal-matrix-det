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
  
  // Set static signals for the TriDiag Core
  io.triDiagCoreIO.rst := io.reset
  io.triDiagCoreIO.clk := clock

  // TODO: Need to update the busy signal if the ROCC is busy
  io.dcplrIO.busy := (cState =/= CtrlState.sIdle) | (mState =/= MemState.sIdle)

  // Internal Registers
  val a_addr_reg = RegInit(0.U(32.W))
  val b_addr_reg = RegInit(0.U(32.W))
  val c_addr_reg = RegInit(0.U(32.W))
  val result_addr_reg = RegInit(0.U(32.W))
  val a_size_reg = RegInit(0.U(4.W))
  val b_size_reg = RegInit(0.U(4.W))
  val c_size_reg = RegInit(0.U(4.W))
  val ready_check_reg = RegInit(false.B)
  val counter_reg = RegInit(0.U(4.W))

  // States (C - Controller, M - Memory)
  val cState = RegInit(CtrlState.sIdle)
  val cStateWire = WireDefault(cState)
  val mState = RegInit(MemState.sIdle)
  val mStateWire = WireDefault(mState)

  // Helper Wires
  val addrWire = Wire(UInt(32.W))
  val curr_size = Wire(UInt(4.W))
  val data_wr_done = mState === MemState.sIdle
  val data_ld_done = mState === MemState.sIdle

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

  // Default TriDiagCoreIO Signals
  io.triDiagCoreIO.we := false.B
  io.triDiagCoreIO.address := 0.U
  io.triDiagCoreIO.write_data := 0.U

  // Address Mapping
  when(cState === CtrlState.sASetup) {
    addrWire := a_addr_reg
    curr_size := a_size_reg
  }.elsewhen(cState === CtrlState.sBSetup) {
    addrWire := b_addr_reg
    curr_size := b_size_reg
  }.elsewhen(cState === CtrlState.sCSetup) {
    addrWire := c_addr_reg
    curr_size := c_size_reg
  }.otherwise {
    addrWire := 0.U
    curr_size := 0.U
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
      io.dcplrIO.start_ready := true.B

      when(io.dcplrIO.a_valid) {
        a_addr_reg := io.dcplrIO.a_addr
        a_size_reg := io.dcplrIO.a_size
        mStateWire := MemState.sReadReq
        cStateWire := CtrlState.sASetup
      }.elsewhen(io.dcplrIO.b_valid) {
        b_addr_reg := io.dcplrIO.b_addr
        b_size_reg := io.dcplrIO.b_size
        mStateWire := MemState.sReadReq
        cStateWire := CtrlState.sBSetup
      }.elsewhen(io.dcplrIO.c_valid) {
        c_addr_reg := io.dcplrIO.c_addr
        c_size_reg := io.dcplrIO.c_size
        mStateWire := MemState.sReadReq
        cStateWire := CtrlState.sCSetup
      }.elsewhen(io.dcplrIO.start_valid) {
        result_addr_reg := io.dcplrIO.result_addr
        io.triDiagCoreIO.we := true.B
        io.triDiagCoreIO.address := TriDiagAddr.CTRL
        io.triDiagCoreIO.write_data := 1.U // Start signal
        cStateWire := CtrlState.sRun
      }
    }
    is(CtrlState.sASetup) {
      when(data_ld_done) {
        cStateWire := CtrlState.sIdle
      }
    }
    is(CtrlState.sBSetup) {
      when(data_ld_done) {
        cStateWire := CtrlState.sIdle
      }
    }
    is(CtrlState.sCSetup) {
      when(data_ld_done) {
        cStateWire := CtrlState.sIdle
      }
    }
    is(CtrlState.sRun) {
      io.triDiagCoreIO.address := TriDiagAddr.STATUS
      when(io.triDiagCoreIO.read_data(0) === ready_check_reg) {
        when(ready_check_reg === false.B) {
          ready_check_reg := true.B
        }.otherwise {
          ready_check_reg := false.B
          mStateWire := MemState.sWriteReq
          cStateWire := CtrlState.sDataWrite
        }
      }
    }
    is(CtrlState.sDataWrite) {
      when(data_wr_done) {
        io.dcplrIO.interrupt := true.B
        cStateWire := CtrlState.sIdle
      }
    }
  }

  // Memory FSM
  switch(mState) {
    is(MemState.sIdle) {
      counter_reg := 0.U
    }
    is(MemState.sReadReq) {
      io.dmem.readReq.valid := true.B
      io.dmem.readReq.bits.addr := addrWire
      io.dmem.readReq.bits.totalBytes := 2.U
      when(io.dmem.readReq.fire()) {
        // While counter_reg less than curr_size, increment the counter and set the address to the next value
        when(counter_reg < (curr_size - 1.U)) {
          counter_reg := counter_reg + 1.U
          addrWire := addrWire + 2.U // Increment by 2 bytes (16 bits)
          mStateWire := MemState.sReadReq // Stay in read request state
        }.otherwise {
          data_ld_done := true.B
          mStateWire := MemState.sIdle
        }
      }
    }
    is(MemState.sWriteReq) {
      // Write result to memory
      when(data_wr_done) {
        mStateWire := MemState.sIdle
      }
    }
  }
}