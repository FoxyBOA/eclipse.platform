/*******************************************************************************
 * Copyright (c) 2026 Foxy BOA and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Foxy BOA - initial implementation, issue #2645
 *******************************************************************************/
package org.eclipse.team.internal.ui.synchronize;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.team.core.TeamException;
import org.eclipse.team.core.subscribers.Subscriber;
import org.eclipse.team.internal.core.subscribers.SubscriberSyncInfoCollector;
import org.eclipse.team.tests.core.mapping.ScopeTestSubscriber;
import org.eclipse.team.ui.synchronize.ISynchronizePageConfiguration;
import org.eclipse.team.ui.synchronize.SubscriberParticipant;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for issue #2645:
 * RefreshSubscriberParticipantJob.doRefresh must split its progress monitor
 * between subscriber.refresh and waitForCollector instead of handing the same
 * monitor instance to both consumers.
 * <p>
 * Without the split, each consumer treats the monitor as exclusively its own
 * and reports its full budget against the parent (typically 100% each), so the
 * parent monitor records ~200%. This is the root cause of the over-100%
 * progress display observed in the Synchronize view for Subversive (and other
 * team providers) refresh jobs (see issue #2645).
 */
public class RefreshSubscriberParticipantJobProgressTests {

	/** Tracks total work reported to a parent monitor. */
	private static class CountingMonitor extends NullProgressMonitor {
		volatile double total;

		@Override
		public void worked(int work) {
			total += work;
		}

		@Override
		public void internalWorked(double work) {
			total += work;
		}
	}

	/**
	 * Subscriber that imitates a typical SCM provider's refresh: it claims the
	 * full budget (100 ticks) against whatever monitor it receives.
	 */
	private static class ConsumingSubscriber extends ScopeTestSubscriber {
		@Override
		public void refresh(IResource[] resources, int depth, IProgressMonitor monitor) {
			SubMonitor sm = SubMonitor.convert(monitor, 100);
			sm.worked(100);
			sm.done();
		}
	}

	/**
	 * Collector whose waitForCollector consumes the full budget (100 ticks)
	 * against whatever monitor it receives, simulating the worst-case behaviour
	 * of the second consumer in {@code doRefresh}.
	 */
	private static class ConsumingCollector extends SubscriberSyncInfoCollector {
		ConsumingCollector(Subscriber subscriber) {
			super(subscriber, new IResource[0]);
		}

		@Override
		public void waitForCollector(IProgressMonitor monitor) {
			SubMonitor sm = SubMonitor.convert(monitor, 100);
			sm.worked(100);
			sm.done();
		}
	}

	/** Exposes {@code RefreshSubscriberParticipantJob.doRefresh} for testing. */
	private static class TestableJob extends RefreshSubscriberParticipantJob {
		private final Subscriber subscriberOverride;

		TestableJob(SubscriberParticipant participant, IResource[] resources, Subscriber subscriber) {
			super(participant, "test-job", "test-task", resources, null);
			this.subscriberOverride = subscriber;
		}

		@Override
		protected Subscriber getSubscriber() {
			return subscriberOverride;
		}

		void invokeDoRefresh(RefreshChangeListener changeDescription, IProgressMonitor monitor) throws TeamException {
			doRefresh(changeDescription, monitor);
		}
	}

	@Test
	public void doRefreshDoesNotOverConsumeParentMonitor() throws Exception {
		ConsumingSubscriber subscriber = new ConsumingSubscriber();
		ConsumingCollector collector = new ConsumingCollector(subscriber);
		SubscriberParticipant participant = new SubscriberParticipant() {
			@Override
			public Subscriber getSubscriber() {
				return subscriber;
			}

			@Override
			public SubscriberSyncInfoCollector getSubscriberSyncInfoCollector() {
				return collector;
			}

			@Override
			protected void initializeConfiguration(ISynchronizePageConfiguration configuration) {
				// not needed for this test
			}
		};

		TestableJob job = new TestableJob(participant, new IResource[0], subscriber);
		RefreshChangeListener changeDescription = new RefreshChangeListener(new IResource[0], collector);

		CountingMonitor parent = new CountingMonitor();
		parent.beginTask("test", 100);
		try {
			job.invokeDoRefresh(changeDescription, parent);
		} finally {
			parent.done();
			collector.dispose();
		}

		// SubMonitor's MINIMUM_RESOLUTION is 1000, so each SubMonitor.convert(parent, 100)
		// claims 1000 ticks against the parent. Without the fix, the same parent is
		// converted once by subscriber.refresh and once by waitForCollector, doubling
		// the parent's total to ~2000. With the fix, doRefresh wraps the parent into a
		// single SubMonitor of 100 ticks and splits it 80/20, so the parent records
		// ~1000 in total — the expected single-pass budget.
		assertTrue(parent.total <= 1500,
				"Parent monitor over-consumed by RefreshSubscriberParticipantJob.doRefresh: total="
						+ parent.total + " (expected ≤ 1500). The same IProgressMonitor was passed"
						+ " to subscriber.refresh and waitForCollector instead of being split.");
	}
}
