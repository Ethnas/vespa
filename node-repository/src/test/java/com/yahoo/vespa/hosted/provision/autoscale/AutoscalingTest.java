// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.autoscale;

import com.yahoo.config.provision.Capacity;
import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.config.provision.Environment;
import com.yahoo.config.provision.Flavor;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.config.provision.NodeResources.DiskSpeed;
import static com.yahoo.config.provision.NodeResources.DiskSpeed.fast;
import static com.yahoo.config.provision.NodeResources.DiskSpeed.slow;
import com.yahoo.config.provision.NodeResources.StorageType;
import static com.yahoo.config.provision.NodeResources.StorageType.remote;
import com.yahoo.config.provision.NodeType;
import com.yahoo.config.provision.RegionName;
import com.yahoo.config.provision.Zone;
import com.yahoo.vespa.hosted.provision.NodeRepository;
import com.yahoo.vespa.hosted.provision.Nodelike;
import com.yahoo.vespa.hosted.provision.provisioning.CapacityPolicies;
import com.yahoo.vespa.hosted.provision.provisioning.HostResourcesCalculator;
import org.junit.Ignore;
import org.junit.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * @author bratseth
 */
public class AutoscalingTest {

    @Test
    public void test_autoscaling_single_content_group() {
        var fixture = AutoscalingTester.fixture().build();

        fixture.tester().clock().advance(Duration.ofDays(1));
        assertTrue("No measurements -> No change", fixture.autoscale().isEmpty());

        fixture.loader().applyCpuLoad(0.7f, 59);
        assertTrue("Too few measurements -> No change", fixture.autoscale().isEmpty());

        fixture.tester().clock().advance(Duration.ofDays(1));
        fixture.loader().applyCpuLoad(0.7f, 120);
        ClusterResources scaledResources = fixture.tester().assertResources("Scaling up since resource usage is too high",
                                                                            9, 1, 2.8,  5.0, 50.0,
                                                                            fixture.autoscale());

        fixture.deploy(Capacity.from(scaledResources));
        assertTrue("Cluster in flux -> No further change", fixture.autoscale().isEmpty());

        fixture.deactivateRetired(Capacity.from(scaledResources));

        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyCpuLoad(0.8f, 3);
        assertTrue("Load change is large, but insufficient measurements for new config -> No change",
                   fixture.autoscale().isEmpty());

        fixture.loader().applyCpuLoad(0.19f, 100);
        assertEquals("Load change is small -> No change", Optional.empty(), fixture.autoscale().target());

        fixture.loader().applyCpuLoad(0.1f, 120);
        fixture.tester().assertResources("Scaling cpu down since usage has gone down significantly",
                                         9, 1, 1.0, 5.0, 50.0,
                                         fixture.autoscale());
    }

    /** Using too many resources for a short period is proof we should scale up regardless of the time that takes. */
    @Test
    public void test_no_autoscaling_with_no_measurements() {
        var fixture = AutoscalingTester.fixture().build();
        System.out.println(fixture.autoscale());
        assertTrue(fixture.autoscale().target().isEmpty());
    }

    /** Using too many resources for a short period is proof we should scale up regardless of the time that takes. */
    @Test
    @Ignore // TODO
    public void test_autoscaling_up_is_fast() {
        var fixture = AutoscalingTester.fixture().build();
        fixture.loader().applyLoad(1.0, 1.0, 1.0, 1);
        fixture.tester().assertResources("Scaling up since resource usage is too high",
                                         10, 1, 9.4, 8.5, 92.6,
                                         fixture.autoscale());
    }

    /** We prefer fewer nodes for container clusters as (we assume) they all use the same disk and memory */
    @Test
    public void test_autoscaling_single_container_group() {
        var fixture = AutoscalingTester.fixture().clusterType(ClusterSpec.Type.container).build();
        fixture.loader().applyCpuLoad(0.25f, 120);
        ClusterResources scaledResources = fixture.tester().assertResources("Scaling up since cpu usage is too high",
                                                                  5, 1, 3.8,  8.0, 50.5,
                                                                  fixture.autoscale());
        fixture.deploy(Capacity.from(scaledResources));
        fixture.loader().applyCpuLoad(0.1f, 120);
        fixture.tester().assertResources("Scaling down since cpu usage has gone down",
                                         4, 1, 2.5, 6.4, 25.5,
                                         fixture.autoscale());
    }

    @Test
    public void autoscaling_handles_disk_setting_changes() {
        var resources = new NodeResources(3, 100, 100, 1, slow);
        var fixture = AutoscalingTester.fixture()
                                       .hostResources(resources)
                                       .initialResources(Optional.of(new ClusterResources(5, 1, resources)))
                                       .capacity(Capacity.from(new ClusterResources(5, 1, resources)))
                                       .build();

        assertTrue(fixture.tester().nodeRepository().nodes().list().owner(fixture.applicationId).stream()
                          .allMatch(n -> n.allocation().get().requestedResources().diskSpeed() == slow));

        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyCpuLoad(0.25, 120);

        // Changing min and max from slow to any
        ClusterResources min = new ClusterResources( 2, 1,
                                                     new NodeResources(1, 1, 1, 1, DiskSpeed.any));
        ClusterResources max = new ClusterResources(20, 1,
                                                    new NodeResources(100, 1000, 1000, 1, DiskSpeed.any));
        var capacity = Capacity.from(min, max);
        ClusterResources scaledResources = fixture.tester().assertResources("Scaling up",
                                                                            14, 1, 1.4,  30.8, 30.8,
                                                                            fixture.autoscale(capacity));
        assertEquals("Disk speed from new capacity is used",
                     DiskSpeed.any, scaledResources.nodeResources().diskSpeed());
        fixture.deploy(Capacity.from(scaledResources));
        assertTrue(fixture.nodes().stream()
                          .allMatch(n -> n.allocation().get().requestedResources().diskSpeed() == DiskSpeed.any));
    }

    @Test
    public void autoscaling_target_preserves_any() {
        NodeResources resources = new NodeResources(1, 10, 10, 1);
        var capacity = Capacity.from(new ClusterResources( 2, 1, resources.with(DiskSpeed.any)),
                                     new ClusterResources( 10, 1, resources.with(DiskSpeed.any)));
        var fixture = AutoscalingTester.fixture()
                                       .capacity(capacity)
                                       .initialResources(Optional.empty())
                                       .build();

        // Redeployment without target: Uses current resource numbers with *requested* non-numbers (i.e disk-speed any)
        assertTrue(fixture.tester().nodeRepository().applications().get(fixture.applicationId).get().cluster(fixture.clusterSpec.id()).get().targetResources().isEmpty());
        fixture.deploy();
        assertEquals(DiskSpeed.any, fixture.nodes().first().get().allocation().get().requestedResources().diskSpeed());

        // Autoscaling: Uses disk-speed any as well
        fixture.deactivateRetired(capacity);
        fixture.tester().clock().advance(Duration.ofDays(1));
        fixture.loader().applyCpuLoad(0.8, 120);
        assertEquals(DiskSpeed.any, fixture.autoscale(capacity).target().get().nodeResources().diskSpeed());
    }

    @Test
    public void autoscaling_respects_upper_limit() {
        var min = new ClusterResources( 2, 1, new NodeResources(1, 1, 1, 1));
        var now = new ClusterResources(5, 1, new NodeResources(1.9, 70, 70, 1));
        var max = new ClusterResources( 6, 1, new NodeResources(2.4, 78, 79, 1));
        var fixture = AutoscalingTester.fixture()
                                       .initialResources(Optional.of(now))
                                       .capacity(Capacity.from(min, max)).build();

        fixture.tester().clock().advance(Duration.ofDays(1));
        fixture.loader().applyLoad(0.25, 0.95, 0.95, 120);
        fixture.tester().assertResources("Scaling up to limit since resource usage is too high",
                                         6, 1, 2.4,  78.0, 79.0,
                                         fixture.autoscale());
    }

    @Test
    public void autoscaling_respects_lower_limit() {
        var min = new ClusterResources( 4, 1, new NodeResources(1.8, 7.4, 8.5, 1));
        var max = new ClusterResources( 6, 1, new NodeResources(2.4, 78, 79, 1));
        var fixture = AutoscalingTester.fixture().capacity(Capacity.from(min, max)).build();

        // deploy
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyLoad(0.05f, 0.05f, 0.05f,  120);
        fixture.tester().assertResources("Scaling down to limit since resource usage is low",
                                         4, 1, 1.8,  7.4, 13.9,
                                         fixture.autoscale());
    }

    @Test
    public void autoscaling_with_unspecified_resources_use_defaults() {
        var min = new ClusterResources( 2, 1, NodeResources.unspecified());
        var max = new ClusterResources( 6, 1, NodeResources.unspecified());
        var fixture = AutoscalingTester.fixture()
                                       .initialResources(Optional.empty())
                                       .capacity(Capacity.from(min, max))
                                       .build();

        NodeResources defaultResources =
                new CapacityPolicies(fixture.tester().nodeRepository()).defaultNodeResources(fixture.clusterSpec, fixture.applicationId, false);

        fixture.tester().assertResources("Min number of nodes and default resources",
                                         2, 1, defaultResources,
                                         fixture.nodes().toResources());
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyLoad(0.25, 0.95, 0.95, 120);
        fixture.tester().assertResources("Scaling up",
                                         5, 1,
                                         defaultResources.vcpu(), defaultResources.memoryGb(), defaultResources.diskGb(),
                                         fixture.autoscale());
    }

    @Test
    public void autoscaling_respects_group_limit() {
        var min = new ClusterResources( 2, 2, new NodeResources(1, 1, 1, 1));
        var now = new ClusterResources(5, 5, new NodeResources(3.0, 10, 10, 1));
        var max = new ClusterResources(18, 6, new NodeResources(100, 1000, 1000, 1));
        var fixture = AutoscalingTester.fixture()
                                       .initialResources(Optional.of(now))
                                       .capacity(Capacity.from(min, max))
                                       .build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyCpuLoad(0.3, 240);
        fixture.tester().assertResources("Scaling up",
                                         6, 6, 3.8,  8.0, 10.0,
                                         fixture.autoscale());
    }

    @Test
    public void test_autoscaling_limits_when_min_equals_max() {
        ClusterResources min = new ClusterResources( 2, 1, new NodeResources(1, 1, 1, 1));
        var fixture = AutoscalingTester.fixture().capacity(Capacity.from(min, min)).build();

        // deploy
        fixture.tester().clock().advance(Duration.ofDays(1));
        fixture.loader().applyCpuLoad(0.25, 120);
        assertTrue(fixture.autoscale().isEmpty());
    }

    @Test
    public void container_prefers_remote_disk_when_no_local_match() {
        var resources = new ClusterResources( 2, 1, new NodeResources(3, 100, 50, 1));
        var local  = new NodeResources(3, 100,  75, 1, fast, StorageType.local);
        var remote = new NodeResources(3, 100,  50, 1, fast, StorageType.remote);
        var fixture = AutoscalingTester.fixture()
                                       .dynamicProvisioning(true)
                                       .clusterType(ClusterSpec.Type.container)
                                       .hostResources(local, remote)
                                       .capacity(Capacity.from(resources))
                                       .initialResources(Optional.of(new ClusterResources(3, 1, resources.nodeResources())))
                                       .build();

        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyLoad(0.01, 0.01, 0.01, 120);
        Autoscaler.Advice suggestion = fixture.suggest();
        fixture.tester().assertResources("Choosing the remote disk flavor as it has less disk",
                                         2, 1, 3.0,  100.0, 10.0,
                                         suggestion);
        assertEquals("Choosing the remote disk flavor as it has less disk",
                     StorageType.remote, suggestion.target().get().nodeResources().storageType());
    }

    @Test
    public void content_prefers_local_disk_when_no_local_match() {
        var resources = new ClusterResources( 2, 1, new NodeResources(3, 100, 50, 1));
        var local  = new NodeResources(3, 100,  75, 1, fast, StorageType.local);
        var remote = new NodeResources(3, 100,  50, 1, fast, StorageType.remote);
        var fixture = AutoscalingTester.fixture()
                                       .dynamicProvisioning(true)
                                       .clusterType(ClusterSpec.Type.content)
                                       .hostResources(local, remote)
                                       .capacity(Capacity.from(resources))
                                       .initialResources(Optional.of(new ClusterResources(3, 1, resources.nodeResources())))
                                       .build();

        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyLoad(0.01, 0.01, 0.01, 120);
        Autoscaler.Advice suggestion = fixture.suggest();
        fixture.tester().assertResources("Always prefers local disk for content",
                                         2, 1, 3.0,  100.0, 75.0,
                                         suggestion);
        assertEquals("Always prefers local disk for content",
                     StorageType.local, suggestion.target().get().nodeResources().storageType());
    }

    @Test
    public void suggestions_ignores_limits() {
        ClusterResources min = new ClusterResources( 2, 1, new NodeResources(1, 1, 1, 1));
        var fixture = AutoscalingTester.fixture().capacity(Capacity.from(min, min)).build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyCpuLoad(1.0, 120);
        fixture.tester().assertResources("Suggesting above capacity limit",
                                         8, 1, 9.3,  5.7, 57.1,
                                         fixture.tester().suggest(fixture.applicationId, fixture.clusterSpec.id(), min, min));
    }

    @Test
    public void not_using_out_of_service_measurements() {
        var fixture = AutoscalingTester.fixture().build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyLoad(0.9, 0.6, 0.7,  1, false, true, 120);
        assertTrue("Not scaling up since nodes were measured while cluster was out of service",
                   fixture.autoscale().isEmpty());
    }

    @Test
    public void not_using_unstable_measurements() {
        var fixture = AutoscalingTester.fixture().build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyLoad(0.9, 0.6, 0.7,  1, true, false, 120);
        assertTrue("Not scaling up since nodes were measured while cluster was out of service",
                   fixture.autoscale().isEmpty());
    }

    @Test
    public void test_autoscaling_group_size_1() {
        var min = new ClusterResources( 2, 2, new NodeResources(1, 1, 1, 1));
        var now = new ClusterResources(5, 5, new NodeResources(3, 100, 100, 1));
        var max = new ClusterResources(20, 20, new NodeResources(10, 1000, 1000, 1));
        var fixture = AutoscalingTester.fixture()
                                       .initialResources(Optional.of(now))
                                       .capacity(Capacity.from(min, max))
                                       .build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyCpuLoad(0.9, 120);
        fixture.tester().assertResources("Scaling the number of groups, but nothing requires us to stay with 1 node per group",
                                         10, 5, 7.7,  40.0, 40.0,
                                         fixture.autoscale());
    }

    @Test
    public void test_autoscaling_groupsize_by_cpu_read_dominated() {
        var min = new ClusterResources( 3, 1, new NodeResources(1, 1, 1, 1));
        var now = new ClusterResources(6, 2, new NodeResources(3, 100, 100, 1));
        var max = new ClusterResources(21, 7, new NodeResources(100, 1000, 1000, 1));
        var fixture = AutoscalingTester.fixture()
                                       .initialResources(Optional.of(now))
                                       .capacity(Capacity.from(min, max))
                                       .build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        Duration timePassed = fixture.loader().addCpuMeasurements(0.25, 120);
        fixture.tester().clock().advance(timePassed.negated());
        fixture.loader().addLoadMeasurements(10, t -> t == 0 ? 20.0 : 10.0, t -> 1.0);
        fixture.tester().assertResources("Scaling up since resource usage is too high, changing to 1 group is cheaper",
                                         10, 1, 2.3, 27.8, 27.8,
                                         fixture.autoscale());
    }

    /** Same as above but mostly write traffic, which favors smaller groups */
    @Test
    public void test_autoscaling_groupsize_by_cpu_write_dominated() {
        var min = new ClusterResources( 3, 1, new NodeResources(1, 1, 1, 1));
        var now = new ClusterResources(6, 2, new NodeResources(3, 100, 100, 1));
        var max = new ClusterResources(21, 7, new NodeResources(100, 1000, 1000, 1));
        var fixture = AutoscalingTester.fixture()
                                       .initialResources(Optional.of(now))
                                       .capacity(Capacity.from(min, max))
                                       .build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        Duration timePassed = fixture.loader().addCpuMeasurements(0.25, 120);
        fixture.tester().clock().advance(timePassed.negated());
        fixture.loader().addLoadMeasurements(10, t -> t == 0 ? 20.0 : 10.0, t -> 100.0);
        fixture.tester().assertResources("Scaling down since resource usage is too high, changing to 1 group is cheaper",
                                         6, 1, 1.0,  50.0, 50.0,
                                         fixture.autoscale());
    }

    @Test
    public void test_autoscaling_group_size() {
        var min = new ClusterResources( 2, 2, new NodeResources(1, 1, 1, 1));
        var now = new ClusterResources(6, 2, new NodeResources(10, 100, 100, 1));
        var max = new ClusterResources(30, 30, new NodeResources(100, 100, 1000, 1));
        var fixture = AutoscalingTester.fixture()
                                       .initialResources(Optional.of(now))
                                       .capacity(Capacity.from(min, max))
                                       .build();
        fixture.tester().clock().advance(Duration.ofDays(1));
        fixture.loader().applyMemLoad(1.0, 1000);
        fixture.tester().assertResources("Increase group size to reduce memory load",
                                         8, 2, 6.5,  96.2, 62.5,
                                         fixture.autoscale());
    }

    @Test
    public void autoscaling_avoids_illegal_configurations() {
        var min = new ClusterResources( 2, 1, new NodeResources(1, 1, 1, 1));
        var now = new ClusterResources(6, 1, new NodeResources(3, 100, 100, 1));
        var max = new ClusterResources(20, 1, new NodeResources(100, 1000, 1000, 1));
        var fixture = AutoscalingTester.fixture()
                                       .initialResources(Optional.of(now))
                                       .capacity(Capacity.from(min, max))
                                       .build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyMemLoad(0.02, 120);
        fixture.tester().assertResources("Scaling down",
                                         6, 1, 3.1, 4.0, 100.0,
                                         fixture.autoscale());
    }

    @Test
    public void scaling_down_only_after_delay() {
        var fixture = AutoscalingTester.fixture().build();
        fixture.loader().applyMemLoad(0.02, 120);
        assertTrue("Too soon  after initial deployment", fixture.autoscale().target().isEmpty());
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyMemLoad(0.02, 120);
        fixture.tester().assertResources("Scaling down since enough time has passed",
                                         6, 1, 1.2, 4.0, 80.0,
                                         fixture.autoscale());
    }

    @Test
    public void test_autoscaling_considers_real_resources() {
        { // No memory tax
            var fixture = AutoscalingTester.fixture()
                                           .resourceCalculator(new OnlySubtractingWhenForecastingCalculator(0))
                                           .build();
            fixture.loader().applyLoad(1.0, 1.0, 0.7, 1000);
            fixture.tester().assertResources("Scaling up",
                                             9, 1, 5.0, 9.6, 72.9,
                                             fixture.autoscale());
        }

        {
            var fixture = AutoscalingTester.fixture()
                                           .resourceCalculator(new OnlySubtractingWhenForecastingCalculator(3))
                                           .build();
            fixture.loader().applyLoad(1.0, 1.0, 0.7, 1000);
            fixture.tester().assertResources("With 3Gb memory tax, we scale up memory more",
                                             7, 1, 6.4, 15.8, 97.2,
                                             fixture.autoscale());
        }
    }

    @Test
    public void test_autoscaling_with_dynamic_provisioning() {
        ClusterResources min = new ClusterResources( 2, 1, new NodeResources(1, 1, 1, 1));
        ClusterResources max = new ClusterResources(20, 1, new NodeResources(100, 1000, 1000, 1));
        var fixture = AutoscalingTester.fixture()
                                       .dynamicProvisioning(true)
                                       .hostResources(new NodeResources(3, 200, 100, 1, fast, remote),
                                                      new NodeResources(3, 150, 100, 1, fast, remote),
                                                      new NodeResources(3, 100, 100, 1, fast, remote),
                                                      new NodeResources(3,  80, 100, 1, fast, remote))
                                       .capacity(Capacity.from(min, max))
                                       .initialResources(Optional.of(new ClusterResources(5, 1,
                                                                                          new NodeResources(3, 100, 100, 1))))
                                       .build();

        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyMemLoad(0.9, 120);
        var scaledResources = fixture.tester().assertResources("Scaling up since resource usage is too high.",
                                                               8, 1, 3,  80, 57.1,
                                                               fixture.autoscale());
        fixture.deploy(Capacity.from(scaledResources));
        fixture.deactivateRetired(Capacity.from(scaledResources));

        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyMemLoad(0.3, 1000);
        fixture.tester().assertResources("Scaling down since resource usage has gone down",
                                         5, 1, 3, 80, 100.0,
                                         fixture.autoscale());
    }

    @Test
    public void test_autoscaling_considers_read_share() {
        var min = new ClusterResources( 1, 1, new NodeResources(3, 100, 100, 1));
        var max = new ClusterResources(10, 1, new NodeResources(3, 100, 100, 1));
        var fixture = AutoscalingTester.fixture()
                                       .capacity(Capacity.from(min, max))
                                       .build();
        fixture.tester.clock().advance(Duration.ofDays(1));
        fixture.loader().applyCpuLoad(0.25, 120);

        // (no read share stored)
        fixture.tester().assertResources("Advice to scale up since we set aside for bcp by default",
                                         7, 1, 3,  100, 100,
                                         fixture.autoscale());

        fixture.storeReadShare(0.25, 0.5);
        fixture.tester().assertResources("Half of global share is the same as the default assumption used above",
                                         7, 1, 3,  100, 100,
                                         fixture.autoscale());

        fixture.storeReadShare(0.5, 0.5);
        fixture.tester().assertResources("Advice to scale down since we don't need room for bcp",
                                         6, 1, 3,  100, 100,
                                         fixture.autoscale());
    }

    @Test
    public void test_autoscaling_considers_growth_rate() {
        var fixture = AutoscalingTester.fixture().build();

        fixture.tester().clock().advance(Duration.ofDays(2));
        Duration timeAdded = fixture.loader().addLoadMeasurements(100, t -> t == 0 ? 20.0 : 10.0, t -> 0.0);
        fixture.tester.clock().advance(timeAdded.negated());
        fixture.loader().addCpuMeasurements(0.25, 200);

        fixture.tester().assertResources("Scale up since we assume we need 2x cpu for growth when no data scaling time data",
                                         9, 1, 2.1,  5, 50,
                                         fixture.autoscale());

        fixture.setScalingDuration(Duration.ofMinutes(5));
        fixture.tester().clock().advance(Duration.ofDays(2));
        timeAdded = fixture.loader().addLoadMeasurements(100, t -> 10.0 + (t < 50 ? t : 100 - t), t -> 0.0);
        fixture.tester.clock().advance(timeAdded.negated());
        fixture.loader().addCpuMeasurements(0.25, 200);
        fixture.tester().assertResources("Scale down since observed growth is slower than scaling time",
                                         9, 1, 1.8,  5, 50,
                                         fixture.autoscale());

        fixture.setScalingDuration(Duration.ofMinutes(60));
        fixture.tester().clock().advance(Duration.ofDays(2));
        timeAdded = fixture.loader().addLoadMeasurements(100,
                                                         t -> 10.0 + (t < 50 ? t * t * t : 125000 - (t - 49) * (t - 49) * (t - 49)),
                                                         t -> 0.0);
        fixture.tester.clock().advance(timeAdded.negated());
        fixture.loader().addCpuMeasurements(0.25, 200);
        fixture.tester().assertResources("Scale up since observed growth is faster than scaling time",
                                         9, 1, 2.1,  5, 50,
                                         fixture.autoscale());
    }

    @Test
    public void test_autoscaling_considers_query_vs_write_rate() {
        var fixture = AutoscalingTester.fixture().build();

        fixture.loader().addCpuMeasurements(0.4, 220);

        // Why twice the query rate at time = 0?
        // This makes headroom for queries doubling, which we want to observe the effect of here

        fixture.tester().clock().advance(Duration.ofDays(2));
        var timeAdded = fixture.loader().addLoadMeasurements(100, t -> t == 0 ? 20.0 : 10.0, t -> 10.0);
        fixture.tester.clock().advance(timeAdded.negated());
        fixture.loader().addCpuMeasurements(0.4, 200);
        fixture.tester.assertResources("Query and write load is equal -> scale up somewhat",
                                       9, 1, 2.4,  5, 50,
                                       fixture.autoscale());

        fixture.tester().clock().advance(Duration.ofDays(2));
        timeAdded = fixture.loader().addLoadMeasurements(100, t -> t == 0 ? 80.0 : 40.0, t -> 10.0);
        fixture.tester.clock().advance(timeAdded.negated());
        fixture.loader().addCpuMeasurements(0.4, 200);
        // TODO: Ackhually, we scale down here - why?
        fixture.tester().assertResources("Query load is 4x write load -> scale up more",
                                         9, 1, 2.1,  5, 50,
                                         fixture.autoscale());

        fixture.tester().clock().advance(Duration.ofDays(2));
        timeAdded = fixture.loader().addLoadMeasurements(100, t -> t == 0 ? 20.0 : 10.0, t -> 100.0);
        fixture.tester.clock().advance(timeAdded.negated());
        fixture.loader().addCpuMeasurements(0.4, 200);
        fixture.tester().assertResources("Write load is 10x query load -> scale down",
                                         9, 1, 1.1,  5, 50,
                                         fixture.autoscale());

        fixture.tester().clock().advance(Duration.ofDays(2));
        timeAdded = fixture.loader().addLoadMeasurements(100, t -> t == 0 ? 20.0 : 10.0, t-> 0.0);
        fixture.tester.clock().advance(timeAdded.negated());
        fixture.loader().addCpuMeasurements(0.4, 200);
        fixture.tester().assertResources("Query only -> largest possible",
                                         8, 1, 4.9,  5.7, 57.1,
                                         fixture.autoscale());

        fixture.tester().clock().advance(Duration.ofDays(2));
        timeAdded = fixture.loader().addLoadMeasurements(100, t ->  0.0, t -> 10.0);
        fixture.tester.clock().advance(timeAdded.negated());
        fixture.loader().addCpuMeasurements(0.4, 200);
        fixture.tester().assertResources("Write only -> smallest possible",
                                         6, 1, 1.0,  8, 80,
                                         fixture.autoscale());
    }

    @Test
    public void test_autoscaling_in_dev() {
        var fixture = AutoscalingTester.fixture()
                                       .zone(new Zone(Environment.dev, RegionName.from("us-east")))
                                       .build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyLoad(1.0, 1.0, 1.0, 200);
        assertTrue("Not attempting to scale up because policies dictate we'll only get one node",
                   fixture.autoscale().target().isEmpty());
    }

    /** Same setup as test_autoscaling_in_dev(), just with required = true */
    @Test
    public void test_autoscaling_in_dev_with_required_resources() {
        var requiredCapacity =
                Capacity.from(new ClusterResources(2, 1,
                                                   new NodeResources(1, 1, 1, 1, NodeResources.DiskSpeed.any)),
                              new ClusterResources(20, 1,
                                                   new NodeResources(100, 1000, 1000, 1, NodeResources.DiskSpeed.any)),
                              true,
                              true);

        var fixture = AutoscalingTester.fixture()
                                       .capacity(requiredCapacity)
                                       .zone(new Zone(Environment.dev, RegionName.from("us-east")))
                                       .build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyLoad(1.0, 1.0, 1.0, 200);
        fixture.tester().assertResources("We scale even in dev because resources are required",
                                         3, 1, 1.0,  7.7, 83.3,
                                         fixture.autoscale());
    }

    @Test
    public void test_autoscaling_in_dev_with_required_unspecified_resources() {
        var requiredCapacity =
                Capacity.from(new ClusterResources(1, 1, NodeResources.unspecified()),
                              new ClusterResources(3, 1, NodeResources.unspecified()),
                              true,
                              true);

        var fixture = AutoscalingTester.fixture()
                                       .capacity(requiredCapacity)
                                       .zone(new Zone(Environment.dev, RegionName.from("us-east")))
                                       .build();
        fixture.tester().clock().advance(Duration.ofDays(2));
        fixture.loader().applyLoad(1.0, 1.0, 1.0, 200);
        fixture.tester().assertResources("We scale even in dev because resources are required",
                                         3, 1, 1.5,  8, 50,
                                         fixture.autoscale());
    }

    /**
     * This calculator subtracts the memory tax when forecasting overhead, but not when actually
     * returning information about nodes. This is allowed because the forecast is a *worst case*.
     * It is useful here because it ensures that we end up with the same real (and therefore target)
     * resources regardless of tax which makes it easier to compare behavior with different tax levels.
     */
    private static class OnlySubtractingWhenForecastingCalculator implements HostResourcesCalculator {

        private final int memoryTaxGb;

        public OnlySubtractingWhenForecastingCalculator(int memoryTaxGb) {
            this.memoryTaxGb = memoryTaxGb;
        }

        @Override
        public NodeResources realResourcesOf(Nodelike node, NodeRepository nodeRepository) {
            return node.resources();
        }

        @Override
        public NodeResources advertisedResourcesOf(Flavor flavor) {
            return flavor.resources();
        }

        @Override
        public NodeResources requestToReal(NodeResources resources, boolean exclusive) {
            return resources.withMemoryGb(resources.memoryGb() - memoryTaxGb);
        }

        @Override
        public NodeResources realToRequest(NodeResources resources, boolean exclusive) {
            return resources.withMemoryGb(resources.memoryGb() + memoryTaxGb);
        }

        @Override
        public long reservedDiskSpaceInBase2Gb(NodeType nodeType, boolean sharedHost) { return 0; }

    }

}
