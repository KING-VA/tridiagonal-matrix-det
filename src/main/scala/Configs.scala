package tridiagonal-matrix-det

import chipsalliance.rocketchip.config.{Config, Parameters}
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet}

class WithTriDiagDet extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq(
    (p: Parameters) => {
      val tridiagdet = LazyModule.apply(new TriDiagDetAccel(OpcodeSet.custom0)(p))
      tridiagdet
    }
  )
})
