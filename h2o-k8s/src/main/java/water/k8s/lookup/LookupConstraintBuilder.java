package water.k8s.lookup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class LookupConstraintBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger(LookupConstraintBuilder.class);
    private static final int K8S_DEFAULT_CLUSTERING_TIMEOUT_SECONDS = 120;
    private Integer timeoutSeconds;
    private Integer desiredClusterSize;

    public LookupConstraintBuilder() {
        this.timeoutSeconds = null;
        this.desiredClusterSize = null;
    }

    public LookupConstraintBuilder withTimeoutSeconds(Integer timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        return this;
    }

    public LookupConstraintBuilder withDesiredClusterSize(final int desiredClusterSize) {
        this.desiredClusterSize = desiredClusterSize;
        return this;
    }

    public Collection<LookupConstraint> build() {

        final List<LookupConstraint> lookupConstraintList = new ArrayList<>();

        if (timeoutSeconds == null && desiredClusterSize == null) {
            LOGGER.info(String.format("No H2O Node discovery timeout set. Using default timeout of %d seconds.",
                    K8S_DEFAULT_CLUSTERING_TIMEOUT_SECONDS));
            lookupConstraintList.add(new TimeoutConstraint(K8S_DEFAULT_CLUSTERING_TIMEOUT_SECONDS));
        }

        if (timeoutSeconds != null) {
            lookupConstraintList.add(new TimeoutConstraint(timeoutSeconds));
        }
        if (desiredClusterSize != null) {
            lookupConstraintList.add(new ClusterSizeConstraint(desiredClusterSize));
        }
        return lookupConstraintList;
    }
}