idf_component_register(SRCS "pet-esp32.c" "pet_ble.c" "pet_mic.c" "pet_spk.c"
                    "pet_sim.c" "pet_rtc.c" "pet_imu.c" "pet_pwr.c"
                    INCLUDE_DIRS "."
                    REQUIRES bt nvs_flash esp_driver_i2c)

# Demo/test scaffolding: `idf.py build -DPET_DEMO_CAPTURE=1` makes the pet start
# a capture on a timer, so a whole conversation can be driven by speaking at it
# with nothing to touch. See PET_DEMO_CAPTURE in pet-esp32.c.
#
# idf.py -D sets a CMake variable, which is NOT a C macro — without this it
# builds clean and silently contains none of the code.
if(PET_DEMO_CAPTURE)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE PET_DEMO_CAPTURE=1)
endif()

# `-DPET_SIM_FAST=1` compresses the simulation's 6-hour decay into 20 s so the
# loop can be watched rather than waited out. `-DPET_IMU_TUNE=1` logs the peak
# accelerometer deviation each second, for setting SHAKE_LSB from what a real
# shake produces. Both carry the same CMake-cache trap as PET_DEMO_CAPTURE: set
# them explicitly back to 0, and check the ELF, or they persist silently.
if(PET_SIM_FAST)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE PET_SIM_FAST=1)
endif()
if(PET_IMU_TUNE)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE PET_IMU_TUNE=1)
endif()

# `-DPET_LIGHT_SLEEP=1` lets the chip enter light sleep when idle. The biggest
# remaining power lever and the riskiest: it stops peripherals, and the BLE
# link's timing then comes off this board's imprecise internal RC oscillator.
# Same cache trap as the rest.
if(PET_LIGHT_SLEEP)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE PET_LIGHT_SLEEP=1)
endif()

# `-DPET_DEBUG_HUD=1` puts the simulation's scores, the face they imply and a
# live shake meter on the pet's own screen, so the gestures can be tested and
# SHAKE_LSB tuned without a serial cable. Same cache trap as the others.
if(PET_DEBUG_HUD)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE PET_DEBUG_HUD=1)
endif()

# `-DPET_PWR_PROBE=1` reports the AXP2101's power-key configuration and logs what
# each physical button actually produces. Temporary; same cache trap as the rest.
if(PET_PWR_PROBE)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE PET_PWR_PROBE=1)
endif()
