plugins {
    `java-gradle-plugin`
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.androidGradlePlugin.get()}")
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
}

gradlePlugin {
    plugins {
        create("kdelog") {
            id = "workstation-connect.kdelog"
            implementationClass = "com.rutger.workstationconnect.build.WsKdeLogPlugin"
        }
    }
}
