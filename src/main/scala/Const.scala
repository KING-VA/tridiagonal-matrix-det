package tridiagonal-matrix-det

import chisel3._
import chisel3.experimental.ChiselEnum

// TriDiag ISA
// If the size is shorter than 16 for READIN, the data is padded with 1s to the left, consistency must be maintained in that A & C should be 1 value shorter than B
// The flow is as follows (loading must be done in this order -- TODO allow any order):
// 1. READIN_A: Load the A array into the accelerator (RS1 = address, RS2 = size)
// 2. READIN_C: Load the C array into the accelerator (RS1 = address, RS2 = size) here size must be equal to A
// 3. READIN_B: Load the B array into the accelerator (RS1 = address, RS2 = size) here size should be strictly +1 than A & C
// For all readins, the controller will go through the addresses and read in data via DMA until the given size is completed (the rest till 16 will be padded by 1s if less than 16) and call the TriDiagDetCore with this information (read is via the DMA)
// 4. START_COMP: Start the computation (RS1 = address) - this command will start the computation (the return address is stored in the controller until the core is done and returns the result which then the controller uses to write back the result to the given address)
// 5. QUERYSTATUS: Check the status of the accelerator (Idle, Loaded_A, Loaded_C, Loaded_B, WaitingforStart, Running, Done) - this command will check the status and return the status to RD of the instruction
object TriDiagISA {
  val READIN_A = 0.U(7.W) // RS1 sets the address in memory to start reading from & the size of the data is set by RS2
  val READIN_C = 1.U(7.W) // RS1 sets the address in memory to start reading from & the size of the data is set by RS2
  val READIN_B = 2.U(7.W) // RS1 sets the address in memory to start reading from & the size of the data is set by RS2
  val START_COMP = 4.U(7.W) // RS1 will set the memory location to output the result of the calculation
  val QUERYSTATUS = 5.U(7.W) // The status of the accelerator (Idle, Loaded_A, Loaded_C, Loaded_B, WaitingforStart, Running, Done) will be returned to RD of the command -- See Decoupler for more details
}

// TriDiag address map
// 0x00: Control register (start) - Set to write_data[0] 1 to start the calculation
// 0x01: Status register (done) - Read to check if calculation is done
// 0x02: Ack (clear done) - Set address to 0x02 and we to high to clear done signal
// 0x10: a[0] to a[N-2] where the first value is loaded into LSB and N is 16
// 0x20: b[0] to b[N-1] where the first value is loaded into LSB and N is 16
// 0x30: c[0] to c[N-2] where the first value is loaded into LSB and N is 16
// 0x40: det (determinant result)
object TriDiagAddr {
  val CTRL = 0.U(8.W)
  val STATUS = 1.U(8.W)
  val ACK = 2.U(8.W)
  val A_ARRAY = 16.U(8.W)
  val B_ARRAY = 32.U(8.W)
  val C_ARRAY = 48.U(8.W)
  val RESULT = 64.U(8.W)
}

// Main Controller States
object CtrlState extends ChiselEnum {
  val sIdle, sASetup, sBSetup, sCSetup, sRun, sDataWrite, Error = Value
}

// Memory Controller States
object MemState extends ChiselEnum {
  val sIdle, sReadReq, sWriteReq = Value
}

// Create the status enum for the controller
object ControllerStatus {
  val Idle = 0.U(3.W) // Idle state
  val Loaded_A = 1.U(3.W) // A array loaded
  val Loaded_AC = 2.U(3.W) // A & C arrays loaded
  val WaitingforStart = 3.U(3.W) // Waiting for start signal
  val Running = 4.U(3.W) // Running the computation
  val Done = 5.U(3.W) // Computation done
  val Error = 6.U(3.W) // Error state
}