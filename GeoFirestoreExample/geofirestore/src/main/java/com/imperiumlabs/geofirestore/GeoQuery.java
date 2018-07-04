package com.imperiumlabs.geofirestore;

import com.google.firebase.firestore.*;
import com.imperiumlabs.geofirestore.core.GeoHash;
import com.imperiumlabs.geofirestore.core.GeoHashQuery;
import com.imperiumlabs.geofirestore.util.GeoUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A GeoQuery object can be used for geo queries in a given circle. The GeoQuery class is thread safe.
 */

// TO COMPLETE; firestore event listener do not work
public class GeoQuery {
    private static final int KILOMETER_TO_METER = 1000;

    // COMPLETED
    private static class LocationInfo {
        final GeoPoint location;
        final boolean inGeoQuery;
        final GeoHash geoHash;
        final DocumentSnapshot documentSnapshot;

        public LocationInfo(GeoPoint location, boolean inGeoQuery, DocumentSnapshot documentSnapshot) {
            this.location = location;
            this.inGeoQuery = inGeoQuery;
            this.geoHash = new GeoHash(new GeoLocation(location.getLatitude(), location.getLongitude()));
            this.documentSnapshot = documentSnapshot;
        }
    }

    // TO COMPLETE
    private final ChildEventListener childEventLister = new ChildEventListener() {
        @Override
        public void onChildAdded(DataSnapshot dataSnapshot, String s) {
            synchronized (GeoQuery.this) {
                GeoQuery.this.childAdded(dataSnapshot);
            }
        }

        @Override
        public void onChildChanged(DataSnapshot dataSnapshot, String s) {
            synchronized (GeoQuery.this) {
                GeoQuery.this.childChanged(dataSnapshot);
            }
        }

        @Override
        public void onChildRemoved(DataSnapshot dataSnapshot) {
            synchronized (GeoQuery.this) {
                GeoQuery.this.childRemoved(dataSnapshot);
            }
        }

        @Override
        public synchronized void onChildMoved(DataSnapshot dataSnapshot, String s) {
            // ignore, this should be handled by onChildChanged
        }

        @Override
        public synchronized void onCancelled(DatabaseError databaseError) {
            // ignore, our API does not support onCancelled
        }
    };

    // COMPLETED
    private final GeoFirestore geoFirestore;
    private final Set<GeoQueryDataEventListener> eventListeners = new HashSet<>();
    private final Map<GeoHashQuery, Query> firestoreQueries = new HashMap<>();
    private final Set<GeoHashQuery> outstandingQueries = new HashSet<>();
    private final Map<String, LocationInfo> locationInfos = new HashMap<>();
    private GeoPoint center;
    private double radius;
    private Set<GeoHashQuery> queries;

    /**
     * Creates a new GeoQuery object centered at the given location and with the given radius.
     * @param geoFirestore The GeoFirestore object this GeoQuery uses
     * @param center The center of this query
     * @param radius The radius of the query, in kilometers. The maximum radius that is
     * supported is about 8587km. If a radius bigger than this is passed we'll cap it.
     */

    // COMPLETED
    GeoQuery(GeoFirestore geoFirestore, GeoPoint center, double radius) {
        this.geoFirestore = geoFirestore;
        this.center = center;
        this.radius = radius * KILOMETER_TO_METER; // Convert from kilometers to meters.
    }

    // COMPLETED
    private boolean locationIsInQuery(GeoPoint location) {
        return GeoUtils.distance(new GeoLocation(location.getLatitude(), location.getLongitude()), new GeoLocation(center.getLatitude(), center.getLongitude())) <= this.radius;
    }

    // COMPLETED
    private void updateLocationInfo(final DocumentSnapshot documentSnapshot, final GeoPoint location) {
        String documentID = documentSnapshot.getId();
        LocationInfo oldInfo = this.locationInfos.get(documentID);
        boolean isNew = oldInfo == null;
        final boolean changedLocation = oldInfo != null && !oldInfo.location.equals(location);
        boolean wasInQuery = oldInfo != null && oldInfo.inGeoQuery;

        boolean isInQuery = this.locationIsInQuery(location);
        if ((isNew || !wasInQuery) && isInQuery) {
            for (final GeoQueryDataEventListener listener: this.eventListeners) {
                this.geoFirestore.raiseEvent(new Runnable() {
                    @Override
                    public void run() {
                        listener.onDocumentEntered(documentSnapshot, location);
                    }
                });
            }
        } else if (!isNew && isInQuery) {
            for (final GeoQueryDataEventListener listener: this.eventListeners) {
                this.geoFirestore.raiseEvent(new Runnable() {
                    @Override
                    public void run() {
                        if (changedLocation) {
                            listener.onDocumentMoved(documentSnapshot, location);
                        }

                        listener.onDocumentChanged(documentSnapshot, location);
                    }
                });
            }
        } else if (wasInQuery && !isInQuery) {
            for (final GeoQueryDataEventListener listener: this.eventListeners) {
                this.geoFirestore.raiseEvent(new Runnable() {
                    @Override
                    public void run() {
                        listener.onDocumentExited(documentSnapshot);
                    }
                });
            }
        }
        LocationInfo newInfo = new LocationInfo(location, this.locationIsInQuery(location), documentSnapshot);
        this.locationInfos.put(documentID, newInfo);
    }

    // COMPLETED
    private boolean geoHashQueriesContainGeoHash(GeoHash geoHash) {
        if (this.queries == null) {
            return false;
        }
        for (GeoHashQuery query: this.queries) {
            if (query.containsGeoHash(geoHash)) {
                return true;
            }
        }
        return false;
    }

    // TO COMPLETE
    private void reset() {
        for(Map.Entry<GeoHashQuery, Query> entry: this.firestoreQueries.entrySet()) {
            entry.getValue().removeEventListener(this.childEventLister);
        }
        this.outstandingQueries.clear();
        this.firestoreQueries.clear();
        this.queries = null;
        this.locationInfos.clear();
    }

    // COMPLETED
    private boolean hasListeners() {
        return !this.eventListeners.isEmpty();
    }

    // COMPLETED
    private boolean canFireReady() {
        return this.outstandingQueries.isEmpty();
    }

    // COMPLETED
    private void checkAndFireReady() {
        if (canFireReady()) {
            for (final GeoQueryDataEventListener listener: this.eventListeners) {
                this.geoFirestore.raiseEvent(new Runnable() {
                    @Override
                    public void run() {
                        listener.onGeoQueryReady();
                    }
                });
            }
        }
    }

    // TO COMPLETE
    private void addValueToReadyListener(final Query firebase, final GeoHashQuery query) {
        firebase.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                synchronized (GeoQuery.this) {
                    GeoQuery.this.outstandingQueries.remove(query);
                    GeoQuery.this.checkAndFireReady();
                }
            }

            @Override
            public void onCancelled(final DatabaseError databaseError) {
                synchronized (GeoQuery.this) {
                    for (final GeoQueryDataEventListener listener : GeoQuery.this.eventListeners) {
                        GeoQuery.this.geoFire.raiseEvent(new Runnable() {
                            @Override
                            public void run() {
                                listener.onGeoQueryError(databaseError);
                            }
                        });
                    }
                }
            }
        });
    }

    // TO COMPLETE
    private void setupQueries() {
        Set<GeoHashQuery> oldQueries = (this.queries == null) ? new HashSet<GeoHashQuery>() : this.queries;
        Set<GeoHashQuery> newQueries = GeoHashQuery.queriesAtLocation(new GeoLocation(center.getLatitude(), center.getLongitude()), radius);
        this.queries = newQueries;
        for (GeoHashQuery query: oldQueries) {
            if (!newQueries.contains(query)) {
                firestoreQueries.get(query).removeEventListener(this.childEventLister);
                firestoreQueries.remove(query);
                outstandingQueries.remove(query);
            }
        }
        for (final GeoHashQuery query: newQueries) {
            if (!oldQueries.contains(query)) {
                outstandingQueries.add(query);
                CollectionReference collectionReference = this.geoFirestore.getCollectionReference();
                Query firestoreQuery = collectionReference.orderBy("g").startAt(query.getStartValue()).endAt(query.getEndValue());
                firestoreQuery.addChildEventListener(this.childEventLister);
                addValueToReadyListener(firestoreQuery, query);
                firestoreQueries.put(query, firestoreQuery);
            }
        }
        for (Map.Entry<String, LocationInfo> info: this.locationInfos.entrySet()) {
            LocationInfo oldLocationInfo = info.getValue();

            if (oldLocationInfo != null) {
                updateLocationInfo(oldLocationInfo.documentSnapshot, oldLocationInfo.location);
            }
        }
        // remove locations that are not part of the geo query anymore
        Iterator<Map.Entry<String, LocationInfo>> it = this.locationInfos.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, LocationInfo> entry = it.next();
            if (!this.geoHashQueriesContainGeoHash(entry.getValue().geoHash)) {
                it.remove();
            }
        }

        checkAndFireReady();
    }

    // COMPLETED
    private void childAdded(DocumentSnapshot documentSnapshot) {
        GeoPoint location = GeoFirestore.getLocationValue(documentSnapshot);
        if (location != null) {
            this.updateLocationInfo(documentSnapshot, location);
        } else {
            throw new AssertionError("Got DocumentSnapshot without location with key " + documentSnapshot.getId());
        }
    }

    // COMPLETED
    private void childChanged(DocumentSnapshot documentSnapshot) {
        GeoPoint location = GeoFirestore.getLocationValue(documentSnapshot);
        if (location != null) {
            this.updateLocationInfo(documentSnapshot, location);
        } else {
            throw new AssertionError("Got DocumentSnapshot without location with key " + documentSnapshot.getId());
        }
    }

    // COMPLETED
    private void childRemoved(DocumentSnapshot documentSnapshot) {
        final String documentID = documentSnapshot.getId();
        final LocationInfo info = this.locationInfos.get(documentID);
        if (info != null) {
            DocumentSnapshot docSnap = this.geoFirestore.getRefForDocumentID(documentID).get().getResult();
            synchronized (GeoQuery.this) {
                GeoPoint location = GeoFirestore.getLocationValue(docSnap);
                GeoHash hash = (location != null) ? new GeoHash(new GeoLocation(location.getLatitude(), location.getLongitude())) : null;
                if (hash == null || !GeoQuery.this.geoHashQueriesContainGeoHash(hash)) {
                    final LocationInfo locInfo = locationInfos.remove(documentID);
                    if (locInfo != null && locInfo.inGeoQuery) {
                        for (final GeoQueryDataEventListener listener: GeoQuery.this.eventListeners) {
                            GeoQuery.this.geoFirestore.raiseEvent(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onDocumentExited(locInfo.documentSnapshot);
                                }
                            });
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds a new GeoQueryEventListener to this GeoQuery.
     *
     * @throws IllegalArgumentException If this listener was already added
     *
     * @param listener The listener to add
     */

    // COMPLETED
    public synchronized void addGeoQueryEventListener(final GeoQueryEventListener listener) {
        addGeoQueryDataEventListener(new EventListenerBridge(listener));
    }

    /**
     * Adds a new GeoQueryEventListener to this GeoQuery.
     *
     * @throws IllegalArgumentException If this listener was already added
     *
     * @param listener The listener to add
     */

    // COMPLETED
    public synchronized void addGeoQueryDataEventListener(final GeoQueryDataEventListener listener) {
        if (eventListeners.contains(listener)) {
            throw new IllegalArgumentException("Added the same listener twice to a GeoQuery!");
        }
        eventListeners.add(listener);
        if (this.queries == null) {
            this.setupQueries();
        } else {
            for (final Map.Entry<String, LocationInfo> entry: this.locationInfos.entrySet()) {
                final String key = entry.getKey();
                final LocationInfo info = entry.getValue();

                if (info.inGeoQuery) {
                    this.geoFirestore.raiseEvent(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDocumentEntered(info.documentSnapshot, info.location);
                        }
                    });
                }
            }
            if (this.canFireReady()) {
                this.geoFirestore.raiseEvent(new Runnable() {
                    @Override
                    public void run() {
                        listener.onGeoQueryReady();
                    }
                });
            }
        }
    }

    /**
     * Removes an event listener.
     *
     * @throws IllegalArgumentException If the listener was removed already or never added
     *
     * @param listener The listener to remove
     */

    // COMPLETED
    public synchronized void removeGeoQueryEventListener(GeoQueryEventListener listener) {
        removeGeoQueryEventListener(new EventListenerBridge(listener));
    }

    /**
     * Removes an event listener.
     *
     * @throws IllegalArgumentException If the listener was removed already or never added
     *
     * @param listener The listener to remove
     */

    // COMPLETED
    public synchronized void removeGeoQueryEventListener(final GeoQueryDataEventListener listener) {
        if (!eventListeners.contains(listener)) {
            throw new IllegalArgumentException("Trying to remove listener that was removed or not added!");
        }
        eventListeners.remove(listener);
        if (!this.hasListeners()) {
            reset();
        }
    }

    /**
     * Removes all event listeners from this GeoQuery.
     */

    // COMPLETED
    public synchronized void removeAllListeners() {
        eventListeners.clear();
        reset();
    }

    /**
     * Returns the current center of this query.
     * @return The current center
     */

    // COMPLETED
    public synchronized GeoPoint getCenter() {
        return center;
    }

    /**
     * Sets the new center of this query and triggers new events if necessary.
     * @param center The new center
     */

    // COMPLETED
    public synchronized void setCenter(GeoPoint center) {
        this.center = center;
        if (this.hasListeners()) {
            this.setupQueries();
        }
    }

    /**
     * Returns the radius of the query, in kilometers.
     * @return The radius of this query, in kilometers
     */

    // COMPLETED
    public synchronized double getRadius() {
        // convert from meters
        return radius / KILOMETER_TO_METER;
    }

    /**
     * Sets the radius of this query, in kilometers, and triggers new events if necessary.
     * @param radius The radius of the query, in kilometers. The maximum radius that is
     * supported is about 8587km. If a radius bigger than this is passed we'll cap it.
     */

    // COMPLETED
    public synchronized void setRadius(double radius) {
        // convert to meters
        this.radius = GeoUtils.capRadius(radius) * KILOMETER_TO_METER;
        if (this.hasListeners()) {
            this.setupQueries();
        }
    }

    /**
     * Sets the center and radius (in kilometers) of this query, and triggers new events if necessary.
     * @param center The new center
     * @param radius The radius of the query, in kilometers. The maximum radius that is
     * supported is about 8587km. If a radius bigger than this is passed we'll cap it.
     */

    // COMPLETED
    public synchronized void setLocation(GeoPoint center, double radius) {
        this.center = center;
        // convert radius to meters
        this.radius = GeoUtils.capRadius(radius) * KILOMETER_TO_METER;
        if (this.hasListeners()) {
            this.setupQueries();
        }
    }
}