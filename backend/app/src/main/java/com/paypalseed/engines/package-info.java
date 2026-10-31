/**
 * Several engine jars share this package, so its Modulith declaration lives in the app. OPEN lets
 * every module use any engine type, not only an {@code api} package.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.paypalseed.engines;
