idf_component_register(
    SRCS "cycling_power.c"
    INCLUDE_DIRS "include"
    REQUIRES bt nvs_flash bridge_state
)
