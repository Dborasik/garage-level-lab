package dev.radixen.garagelevel.model;

public final class GarageEntrance {
    public final String osmId;
    public final double latitude;
    public final double longitude;
    public final Integer logicalLevel;
    public final String levelRef;

    public GarageEntrance(String osmId, double latitude, double longitude, Integer logicalLevel, String levelRef) {
        this.osmId = osmId;
        this.latitude = latitude;
        this.longitude = longitude;
        this.logicalLevel = logicalLevel;
        this.levelRef = levelRef;
    }
}
