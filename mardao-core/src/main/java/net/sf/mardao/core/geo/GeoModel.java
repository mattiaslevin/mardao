package net.sf.mardao.core.geo;

import java.util.Collection;

/**
 *
 * @author os
 */
public interface GeoModel {
    DLocation getLocation();
    void setGeoboxes(Collection<Long> geoboxes);
}
