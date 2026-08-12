idf_component_register(
    SRCS "app_main.c"
    INCLUDE_DIRS "."
    REQUIRES
        nvs_flash
        bridge_state
        bridge_config
        cycling_power
        bike_client
        network_manager
        web_server
        status_led
)

# Reuse the private local Android prefill without committing the real serial.
set(LOCAL_PROPERTIES "${CMAKE_CURRENT_LIST_DIR}/../../local.properties")
if(EXISTS "${LOCAL_PROPERTIES}")
    file(STRINGS "${LOCAL_PROPERTIES}" C1_SN_LINE REGEX "^c1\\.defaultSn=CC[A-Za-z0-9]{14}$")
    if(C1_SN_LINE)
        string(REGEX REPLACE "^c1\\.defaultSn=" "" C1_LOCAL_DEFAULT_SN "${C1_SN_LINE}")
        target_compile_definitions(${COMPONENT_LIB} PRIVATE C1_DEFAULT_SN="${C1_LOCAL_DEFAULT_SN}")
        idf_component_get_property(BRIDGE_CONFIG_LIB bridge_config COMPONENT_LIB)
        target_compile_definitions(${BRIDGE_CONFIG_LIB} PRIVATE C1_DEFAULT_SN="${C1_LOCAL_DEFAULT_SN}")
    endif()
endif()
