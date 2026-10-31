package com.paypalseed;

import org.springframework.test.context.TestPropertySource;

/** The same oracle check with close of business scanning every active loan instead of the timer index. */
@TestPropertySource(properties = "seed.cob.mode=SCAN")
class CobDifferentialScanModeIT extends CobDifferentialIT {
}
