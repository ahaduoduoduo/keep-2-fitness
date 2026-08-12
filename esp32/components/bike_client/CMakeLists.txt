idf_component_register(
    SRCS "bike_client.c"
    INCLUDE_DIRS "include"
    REQUIRES bt esp_timer bridge_state bridge_config c1_protocol
)
