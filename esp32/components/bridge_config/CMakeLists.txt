idf_component_register(
    SRCS "bridge_config.c"
    INCLUDE_DIRS "include"
    REQUIRES nvs_flash esp_system bridge_state
)
