rootProject.name = "paypal-seed-backend"

include("app")
include("architecture-tests")

// platform
include("platform:kernel")
include("platform:persistence")
include("platform:messaging")
include("platform:idempotency")
include("platform:security")
include("platform:observability")
include("platform:testing")

// engines
include("engines:schedule")
include("engines:rules")
include("engines:assessment")

// modules
include("modules:identity")
include("modules:savings")
include("modules:lending")
include("modules:underwriting")
include("modules:hardship")
include("modules:credit-record")
include("modules:ledger")
include("modules:governance")
include("modules:payments")
include("modules:reserve")
include("modules:fraud")
include("modules:engagement")
include("modules:agent-gateway")
include("modules:desk-query")
