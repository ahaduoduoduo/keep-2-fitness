idf_component_register(
    SRCS "web_server.c"
    INCLUDE_DIRS "include"
    REQUIRES esp_http_server esp_timer json bridge_state bridge_config bike_client network_manager
    EMBED_FILES
        "web/index.html"
        "web/app.css"
        "web/app.js"
        "web/qr.js"
        "web/jsqr.min.js"
        "web/bebas-neue.woff2"
        "web/barlow-condensed.woff2"
)
