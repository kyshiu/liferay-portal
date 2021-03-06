/**
 * Copyright (c) 2000-2012 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portlet.blogs.service.impl;

import com.liferay.portal.kernel.dao.orm.QueryDefinition;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.search.Indexer;
import com.liferay.portal.kernel.search.IndexerRegistryUtil;
import com.liferay.portal.kernel.util.ContentTypes;
import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.HtmlUtil;
import com.liferay.portal.kernel.util.HttpUtil;
import com.liferay.portal.kernel.util.OrderByComparator;
import com.liferay.portal.kernel.util.ParamUtil;
import com.liferay.portal.kernel.util.PropsKeys;
import com.liferay.portal.kernel.util.SetUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.StringPool;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.kernel.workflow.WorkflowHandlerRegistryUtil;
import com.liferay.portal.kernel.workflow.WorkflowInstance;
import com.liferay.portal.kernel.workflow.WorkflowInstanceManagerUtil;
import com.liferay.portal.model.Group;
import com.liferay.portal.model.ResourceConstants;
import com.liferay.portal.model.User;
import com.liferay.portal.model.WorkflowInstanceLink;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portal.service.ServiceContextUtil;
import com.liferay.portal.util.Portal;
import com.liferay.portal.util.PortalUtil;
import com.liferay.portal.util.PortletKeys;
import com.liferay.portal.util.PrefsPropsUtil;
import com.liferay.portal.util.PropsValues;
import com.liferay.portal.util.SubscriptionSender;
import com.liferay.portlet.asset.model.AssetEntry;
import com.liferay.portlet.asset.model.AssetLinkConstants;
import com.liferay.portlet.blogs.EntryContentException;
import com.liferay.portlet.blogs.EntryDisplayDateException;
import com.liferay.portlet.blogs.EntrySmallImageNameException;
import com.liferay.portlet.blogs.EntrySmallImageSizeException;
import com.liferay.portlet.blogs.EntryTitleException;
import com.liferay.portlet.blogs.model.BlogsEntry;
import com.liferay.portlet.blogs.service.base.BlogsEntryLocalServiceBaseImpl;
import com.liferay.portlet.blogs.social.BlogsActivityKeys;
import com.liferay.portlet.blogs.util.BlogsUtil;
import com.liferay.portlet.blogs.util.LinkbackProducerUtil;
import com.liferay.portlet.blogs.util.comparator.EntryDisplayDateComparator;
import com.liferay.portlet.social.model.SocialActivityConstants;
import com.liferay.portlet.trash.model.TrashEntry;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;

import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.portlet.PortletPreferences;

import net.htmlparser.jericho.Source;
import net.htmlparser.jericho.StartTag;

/**
 * @author Brian Wing Shun Chan
 * @author Wilson S. Man
 * @author Raymond Augé
 * @author Thiago Moreira
 * @author Juan Fernández
 * @author Zsolt Berentey
 */
public class BlogsEntryLocalServiceImpl extends BlogsEntryLocalServiceBaseImpl {

	public BlogsEntry addEntry(
			long userId, String title, String description, String content,
			int displayDateMonth, int displayDateDay, int displayDateYear,
			int displayDateHour, int displayDateMinute, boolean allowPingbacks,
			boolean allowTrackbacks, String[] trackbacks, boolean smallImage,
			String smallImageURL, String smallImageFileName,
			InputStream smallImageInputStream, ServiceContext serviceContext)
		throws PortalException, SystemException {

		// Entry

		User user = userPersistence.findByPrimaryKey(userId);
		long groupId = serviceContext.getScopeGroupId();

		Date displayDate = PortalUtil.getDate(
			displayDateMonth, displayDateDay, displayDateYear, displayDateHour,
			displayDateMinute, user.getTimeZone(),
			new EntryDisplayDateException());

		byte[] smallImageBytes = null;

		try {
			if ((smallImageInputStream != null) && smallImage) {
				smallImageBytes = FileUtil.getBytes(smallImageInputStream);
			}
		}
		catch (IOException ioe) {
		}

		Date now = new Date();

		validate(
			title, content, smallImage, smallImageURL, smallImageFileName,
			smallImageBytes);

		long entryId = counterLocalService.increment();

		BlogsEntry entry = blogsEntryPersistence.create(entryId);

		entry.setUuid(serviceContext.getUuid());
		entry.setGroupId(groupId);
		entry.setCompanyId(user.getCompanyId());
		entry.setUserId(user.getUserId());
		entry.setUserName(user.getFullName());
		entry.setCreateDate(serviceContext.getCreateDate(now));
		entry.setModifiedDate(serviceContext.getModifiedDate(now));
		entry.setTitle(title);
		entry.setUrlTitle(
			getUniqueUrlTitle(entryId, title, null, serviceContext));
		entry.setDescription(description);
		entry.setContent(content);
		entry.setDisplayDate(displayDate);
		entry.setAllowPingbacks(allowPingbacks);
		entry.setAllowTrackbacks(allowTrackbacks);
		entry.setSmallImage(smallImage);
		entry.setSmallImageId(counterLocalService.increment());
		entry.setSmallImageURL(smallImageURL);
		entry.setStatus(WorkflowConstants.STATUS_DRAFT);
		entry.setStatusDate(serviceContext.getModifiedDate(now));
		entry.setExpandoBridgeAttributes(serviceContext);

		blogsEntryPersistence.update(entry, false);

		// Resources

		if (serviceContext.isAddGroupPermissions() ||
			serviceContext.isAddGuestPermissions()) {

			addEntryResources(
				entry, serviceContext.isAddGroupPermissions(),
				serviceContext.isAddGuestPermissions());
		}
		else {
			addEntryResources(
				entry, serviceContext.getGroupPermissions(),
				serviceContext.getGuestPermissions());
		}

		// Small image

		saveImages(smallImage, entry.getSmallImageId(), smallImageBytes);

		// Asset

		updateAsset(
			userId, entry, serviceContext.getAssetCategoryIds(),
			serviceContext.getAssetTagNames(),
			serviceContext.getAssetLinkEntryIds());

		// Message boards

		if (PropsValues.BLOGS_ENTRY_COMMENTS_ENABLED) {
			mbMessageLocalService.addDiscussionMessage(
				userId, entry.getUserName(), groupId,
				BlogsEntry.class.getName(), entryId,
				WorkflowConstants.ACTION_PUBLISH);
		}

		// Workflow

		if ((trackbacks != null) && (trackbacks.length > 0)) {
			serviceContext.setAttribute("trackbacks", trackbacks);
		}
		else {
			serviceContext.setAttribute("trackbacks", null);
		}

		WorkflowHandlerRegistryUtil.startWorkflowInstance(
			user.getCompanyId(), groupId, userId, BlogsEntry.class.getName(),
			entry.getEntryId(), entry, serviceContext);

		return entry;
	}

	public void addEntryResources(
			BlogsEntry entry, boolean addGroupPermissions,
			boolean addGuestPermissions)
		throws PortalException, SystemException {

		resourceLocalService.addResources(
			entry.getCompanyId(), entry.getGroupId(), entry.getUserId(),
			BlogsEntry.class.getName(), entry.getEntryId(), false,
			addGroupPermissions, addGuestPermissions);
	}

	public void addEntryResources(
			BlogsEntry entry, String[] groupPermissions,
			String[] guestPermissions)
		throws PortalException, SystemException {

		resourceLocalService.addModelResources(
			entry.getCompanyId(), entry.getGroupId(), entry.getUserId(),
			BlogsEntry.class.getName(), entry.getEntryId(), groupPermissions,
			guestPermissions);
	}

	public void addEntryResources(
			long entryId, boolean addGroupPermissions,
			boolean addGuestPermissions)
		throws PortalException, SystemException {

		BlogsEntry entry = blogsEntryPersistence.findByPrimaryKey(entryId);

		addEntryResources(entry, addGroupPermissions, addGuestPermissions);
	}

	public void addEntryResources(
			long entryId, String[] groupPermissions, String[] guestPermissions)
		throws PortalException, SystemException {

		BlogsEntry entry = blogsEntryPersistence.findByPrimaryKey(entryId);

		addEntryResources(entry, groupPermissions, guestPermissions);
	}

	public void deleteEntries(long groupId)
		throws PortalException, SystemException {

		for (BlogsEntry entry : blogsEntryPersistence.findByGroupId(groupId)) {
			deleteEntry(entry);
		}
	}

	public void deleteEntry(BlogsEntry entry)
		throws PortalException, SystemException {

		// Entry

		blogsEntryPersistence.remove(entry);

		// Resources

		resourceLocalService.deleteResource(
			entry.getCompanyId(), BlogsEntry.class.getName(),
			ResourceConstants.SCOPE_INDIVIDUAL, entry.getEntryId());

		// Image

		imageLocalService.deleteImage(entry.getSmallImageId());

		// Subscriptions

		subscriptionLocalService.deleteSubscriptions(
			entry.getCompanyId(), BlogsEntry.class.getName(),
			entry.getEntryId());

		// Statistics

		blogsStatsUserLocalService.updateStatsUser(
			entry.getGroupId(), entry.getUserId());

		// Asset

		assetEntryLocalService.deleteEntry(
			BlogsEntry.class.getName(), entry.getEntryId());

		// Expando

		expandoValueLocalService.deleteValues(
			BlogsEntry.class.getName(), entry.getEntryId());

		// Message boards

		mbMessageLocalService.deleteDiscussionMessages(
			BlogsEntry.class.getName(), entry.getEntryId());

		// Ratings

		ratingsStatsLocalService.deleteStats(
			BlogsEntry.class.getName(), entry.getEntryId());

		// Trash

		trashEntryLocalService.deleteEntry(
			BlogsEntry.class.getName(), entry.getEntryId());

		// Indexer

		Indexer indexer = IndexerRegistryUtil.nullSafeGetIndexer(
			BlogsEntry.class);

		indexer.delete(entry);

		// Workflow

		workflowInstanceLinkLocalService.deleteWorkflowInstanceLinks(
			entry.getCompanyId(), entry.getGroupId(),
			BlogsEntry.class.getName(), entry.getEntryId());
	}

	public void deleteEntry(long entryId)
		throws PortalException, SystemException {

		BlogsEntry entry = blogsEntryPersistence.findByPrimaryKey(entryId);

		deleteEntry(entry);
	}

	/**
	 * @deprecated {@link #getCompanyEntries(long, Date, QueryDefinition)}
	 */
	public List<BlogsEntry> getCompanyEntries(
			long companyId, Date displayDate, int status, int start, int end)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, null);

		return getCompanyEntries(companyId, displayDate, queryDefinition);
	}

	/**
	 * @deprecated {@link #getCompanyEntries(long, Date, QueryDefinition)}
	 */
	public List<BlogsEntry> getCompanyEntries(
			long companyId, Date displayDate, int status, int start, int end,
			OrderByComparator obc)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, obc);

		return getCompanyEntries(companyId, displayDate, queryDefinition);
	}

	public List<BlogsEntry> getCompanyEntries(
			long companyId, Date displayDate, QueryDefinition queryDefinition)
		throws SystemException {

		if (queryDefinition.isExcludeStatus()) {
			return blogsEntryPersistence.findByC_LtD_NeS(
				companyId, displayDate, queryDefinition.getStatus(),
				queryDefinition.getStart(), queryDefinition.getEnd(),
				queryDefinition.getOrderByComparator());
		}
		else {
			return blogsEntryPersistence.findByC_LtD_S(
				companyId, displayDate, queryDefinition.getStatus(),
				queryDefinition.getStart(), queryDefinition.getEnd(),
				queryDefinition.getOrderByComparator());
		}
	}

	/**
	 * @deprecated {@link #getCompanyEntriesCount(long, Date, QueryDefinition)}
	 */
	public int getCompanyEntriesCount(
			long companyId, Date displayDate, int status)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(status);

		return getCompanyEntriesCount(companyId, displayDate, queryDefinition);
	}

	public int getCompanyEntriesCount(
			long companyId, Date displayDate, QueryDefinition queryDefinition)
		throws SystemException {

		if (queryDefinition.isExcludeStatus()) {
			return blogsEntryPersistence.countByC_LtD_NeS(
				companyId, displayDate, queryDefinition.getStatus());
		}
		else {
			return blogsEntryPersistence.countByC_LtD_S(
				companyId, displayDate, queryDefinition.getStatus());
		}
	}

	public BlogsEntry[] getEntriesPrevAndNext(long entryId)
		throws PortalException, SystemException {

		BlogsEntry entry = blogsEntryPersistence.findByPrimaryKey(entryId);

		return blogsEntryPersistence.findByG_S_PrevAndNext(
			entry.getEntryId(), entry.getGroupId(),
			WorkflowConstants.STATUS_APPROVED,
			new EntryDisplayDateComparator(true));
	}

	public BlogsEntry getEntry(long entryId)
		throws PortalException, SystemException {

		return blogsEntryPersistence.findByPrimaryKey(entryId);
	}

	public BlogsEntry getEntry(long groupId, String urlTitle)
		throws PortalException, SystemException {

		return blogsEntryPersistence.findByG_UT(groupId, urlTitle);
	}

	/**
	 * @deprecated {@link #getGroupEntries(long, Date, QueryDefinition)}
	 */
	public List<BlogsEntry> getGroupEntries(
			long groupId, Date displayDate, int status, int start, int end)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, null);

		return getGroupEntries(groupId, displayDate, queryDefinition);
	}

	/**
	 * @deprecated {@link #getGroupEntries(long, Date, QueryDefinition)}
	 */
	public List<BlogsEntry> getGroupEntries(
			long groupId, Date displayDate, int status, int start, int end,
			OrderByComparator obc)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, obc);

		return getGroupEntries(groupId, displayDate, queryDefinition);
	}

	public List<BlogsEntry> getGroupEntries(
			long groupId, Date displayDate, QueryDefinition queryDefinition)
		throws SystemException {

		if (queryDefinition.isExcludeStatus()) {
			return blogsEntryPersistence.findByG_LtD_NeS(
				groupId, displayDate, queryDefinition.getStatus(),
				queryDefinition.getStart(), queryDefinition.getEnd(),
				queryDefinition.getOrderByComparator());
		}
		else {
			return blogsEntryPersistence.findByG_LtD_S(
				groupId, displayDate, queryDefinition.getStatus(),
				queryDefinition.getStart(), queryDefinition.getEnd(),
				queryDefinition.getOrderByComparator());
		}
	}

	/**
	 * @deprecated {@link #getGroupEntries(long, QueryDefinition)}
	 */
	public List<BlogsEntry> getGroupEntries(
			long groupId, int status, int start, int end)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, null);

		return getGroupEntries(groupId, queryDefinition);
	}

	/**
	 * @deprecated {@link #getGroupEntries(long, QueryDefinition)}
	 */
	public List<BlogsEntry> getGroupEntries(
			long groupId, int status, int start, int end, OrderByComparator obc)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, obc);

		return getGroupEntries(groupId, queryDefinition);
	}

	public List<BlogsEntry> getGroupEntries(
			long groupId, QueryDefinition queryDefinition)
		throws SystemException {

		if (queryDefinition.isExcludeStatus()) {
			return blogsEntryPersistence.findByG_NeS(
				groupId, queryDefinition.getStatus(),
				queryDefinition.getStart(), queryDefinition.getEnd(),
				queryDefinition.getOrderByComparator());
		}
		else {
			return blogsEntryPersistence.findByG_S(
				groupId, queryDefinition.getStatus(),
				queryDefinition.getStart(), queryDefinition.getEnd(),
				queryDefinition.getOrderByComparator());
		}
	}

	/**
	 * @deprecated {@link #getGroupEntriesCount(long, Date, QueryDefinition)}
	 */
	public int getGroupEntriesCount(long groupId, Date displayDate, int status)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(status);

		return getGroupEntriesCount(groupId, displayDate, queryDefinition);
	}

	public int getGroupEntriesCount(
			long groupId, Date displayDate, QueryDefinition queryDefinition)
		throws SystemException {

		if (queryDefinition.isExcludeStatus()) {
			return blogsEntryPersistence.countByG_LtD_NeS(
				groupId, displayDate, queryDefinition.getStatus());
		}
		else {
			return blogsEntryPersistence.countByG_LtD_S(
				groupId, displayDate, queryDefinition.getStatus());
		}
	}

	/**
	 * @deprecated {@link #getGroupEntriesCount(long, QueryDefinition)}
	 */
	public int getGroupEntriesCount(long groupId, int status)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(status);

		return getGroupEntriesCount(groupId, queryDefinition);
	}

	public int getGroupEntriesCount(
			long groupId, QueryDefinition queryDefinition)
		throws SystemException {

		if (queryDefinition.isExcludeStatus()) {
			return blogsEntryPersistence.countByG_NeS(
				groupId, queryDefinition.getStatus());
		}
		else {
			return blogsEntryPersistence.countByG_S(
				groupId, queryDefinition.getStatus());
		}
	}

	/**
	 * @deprecated {@link #getGroupsEntries(long, long, Date, QueryDefinition)}
	 */
	public List<BlogsEntry> getGroupsEntries(
			long companyId, long groupId, Date displayDate, int status,
			int start, int end)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, null);

		return getGroupsEntries(
			companyId, groupId, displayDate, queryDefinition);
	}

	public List<BlogsEntry> getGroupsEntries(
			long companyId, long groupId, Date displayDate,
			QueryDefinition queryDefinition)
		throws SystemException {

		return blogsEntryFinder.findByGroupIds(
			companyId, groupId, displayDate, queryDefinition);
	}

	/**
	 * @deprecated {@link #getGroupUserEntries(long, long, Date,
	 *             QueryDefinition)}
	 */
	public List<BlogsEntry> getGroupUserEntries(
			long groupId, long userId, Date displayDate, int status, int start,
			int end)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, null);

		return getGroupUserEntries(
			groupId, userId, displayDate, queryDefinition);
	}

	/**
	 * @deprecated {@link #getGroupUserEntries(long, long, Date,
	 *             QueryDefinition)}
	 */
	public List<BlogsEntry> getGroupUserEntries(
			long groupId, long userId, Date displayDate, int status, int start,
			int end, OrderByComparator obc)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, obc);

		return getGroupUserEntries(
			groupId, userId, displayDate, queryDefinition);
	}

	public List<BlogsEntry> getGroupUserEntries(
			long groupId, long userId, Date displayDate,
			QueryDefinition queryDefinition)
		throws SystemException {

		if (queryDefinition.isExcludeStatus()) {
			return blogsEntryPersistence.findByG_U_NeS(
				groupId, userId, queryDefinition.getStatus(),
				queryDefinition.getStart(), queryDefinition.getEnd(),
				queryDefinition.getOrderByComparator());
		}
		else {
			return blogsEntryPersistence.findByG_U_S(
				groupId, userId, queryDefinition.getStatus(),
				queryDefinition.getStart(), queryDefinition.getEnd(),
				queryDefinition.getOrderByComparator());
		}
	}

	/**
	 * @deprecated {@link #getGroupUserEntriesCount(long, long, Date,
	 *             QueryDefinition)}
	 */
	public int getGroupUserEntriesCount(
			long groupId, long userId, Date displayDate, int status)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(status);

		return getGroupUserEntriesCount(
			groupId, userId, displayDate, queryDefinition);
	}

	public int getGroupUserEntriesCount(
			long groupId, long userId, Date displayDate,
			QueryDefinition queryDefinition)
		throws SystemException {

		if (queryDefinition.isExcludeStatus()) {
			return blogsEntryPersistence.countByG_U_LtD_NeS(
				groupId, userId, displayDate, queryDefinition.getStatus());
		}
		else {
			return blogsEntryPersistence.countByG_U_LtD_S(
				groupId, userId, displayDate, queryDefinition.getStatus());
		}
	}

	public List<BlogsEntry> getNoAssetEntries() throws SystemException {
		return blogsEntryFinder.findByNoAssets();
	}

	/**
	 * @deprecated {@link #getOrganizationEntries(long, Date, QueryDefinition)}
	 */
	public List<BlogsEntry> getOrganizationEntries(
			long organizationId, Date displayDate, int status, int start,
			int end)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, null);

		return getOrganizationEntries(
			organizationId, displayDate, queryDefinition);
	}

	/**
	 * @deprecated {@link #getOrganizationEntries(long, Date, QueryDefinition)}
	 */
	public List<BlogsEntry> getOrganizationEntries(
			long organizationId, Date displayDate, int status, int start,
			int end, OrderByComparator obc)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(
			status, start, end, obc);

		return getOrganizationEntries(
			organizationId, displayDate, queryDefinition);
	}

	public List<BlogsEntry> getOrganizationEntries(
			long organizationId, Date displayDate,
			QueryDefinition queryDefinition)
		throws SystemException {

		return blogsEntryFinder.findByOrganizationId(
			organizationId, displayDate, queryDefinition);
	}

	/**
	 * @deprecated {@link #getOrganizationEntriesCount(long, Date,
	 *             QueryDefinition)}
	 */
	public int getOrganizationEntriesCount(
			long organizationId, Date displayDate, int status)
		throws SystemException {

		QueryDefinition queryDefinition = new QueryDefinition(status);

		return getOrganizationEntriesCount(
			organizationId, displayDate, queryDefinition);
	}

	public int getOrganizationEntriesCount(
			long organizationId, Date displayDate,
			QueryDefinition queryDefinition)
		throws SystemException {

		return blogsEntryFinder.countByOrganizationId(
			organizationId, displayDate, queryDefinition);
	}

	public void moveEntriesToTrash(long groupId, long userId)
		throws PortalException, SystemException {

		List<BlogsEntry> entries = blogsEntryPersistence.findByGroupId(groupId);

		for (BlogsEntry entry : entries) {
			moveEntryToTrash(userId, entry);
		}
	}

	public BlogsEntry moveEntryToTrash(long userId, BlogsEntry entry)
		throws PortalException, SystemException {

		// Entry

		int oldStatus = entry.getStatus();

		updateStatus(
			userId, entry.getEntryId(), WorkflowConstants.STATUS_IN_TRASH,
			new ServiceContext());

		// Social

		socialActivityLocalService.addActivity(
			userId, entry.getGroupId(), BlogsEntry.class.getName(),
			entry.getEntryId(), SocialActivityConstants.TYPE_MOVE_TO_TRASH,
			StringPool.BLANK, 0);

		// Trash

		trashEntryLocalService.addTrashEntry(
			userId, entry.getGroupId(), BlogsEntry.class.getName(),
			entry.getEntryId(), oldStatus, null, null);

		// Workflow

		if (oldStatus == WorkflowConstants.STATUS_PENDING) {
			WorkflowInstanceLink workflowInstanceLink =
				workflowInstanceLinkLocalService.getWorkflowInstanceLink(
					entry.getCompanyId(), entry.getGroupId(),
					BlogsEntry.class.getName(), entry.getEntryId());

			WorkflowInstance workflowInstance =
				WorkflowInstanceManagerUtil.getWorkflowInstance(
					entry.getCompanyId(),
					workflowInstanceLink.getWorkflowInstanceId());

			Map<String, Serializable> workflowContext =
				workflowInstance.getWorkflowContext();

			ServiceContext serviceContext = (ServiceContext)workflowContext.get(
				WorkflowConstants.CONTEXT_SERVICE_CONTEXT);

			boolean update = ParamUtil.getBoolean(serviceContext, "update");

			if (update) {
				entry.setStatus(WorkflowConstants.STATUS_DRAFT_FROM_APPROVED);
			}
			else {
				entry.setStatus(WorkflowConstants.STATUS_DRAFT);
			}

			workflowInstanceLinkLocalService.deleteWorkflowInstanceLink(
				workflowInstanceLink.getWorkflowInstanceId());
		}

		return entry;
	}

	public BlogsEntry moveEntryToTrash(long userId, long entryId)
		throws PortalException, SystemException {

		BlogsEntry entry = blogsEntryPersistence.findByPrimaryKey(entryId);

		return moveEntryToTrash(userId, entry);
	}

	public void restoreEntryFromTrash(long userId, long entryId)
		throws PortalException, SystemException {

		// Entry

		TrashEntry trashEntry = trashEntryLocalService.getEntry(
			BlogsEntry.class.getName(), entryId);

		updateStatus(
			userId, entryId, trashEntry.getStatus(), new ServiceContext());

		// Social

		socialActivityCounterLocalService.enableActivityCounters(
			BlogsEntry.class.getName(), entryId);

		socialActivityLocalService.addActivity(
			userId, trashEntry.getGroupId(), BlogsEntry.class.getName(),
			entryId, SocialActivityConstants.TYPE_RESTORE_FROM_TRASH,
			StringPool.BLANK, 0);

		// Trash

		trashEntryLocalService.deleteEntry(trashEntry.getEntryId());
	}

	public void subscribe(long userId, long groupId)
		throws PortalException, SystemException {

		subscriptionLocalService.addSubscription(
			userId, groupId, BlogsEntry.class.getName(), groupId);
	}

	public void unsubscribe(long userId, long groupId)
		throws PortalException, SystemException {

		subscriptionLocalService.deleteSubscription(
			userId, BlogsEntry.class.getName(), groupId);
	}

	public void updateAsset(
			long userId, BlogsEntry entry, long[] assetCategoryIds,
			String[] assetTagNames, long[] assetLinkEntryIds)
		throws PortalException, SystemException {

		boolean visible = false;

		if (entry.isApproved()) {
			visible = true;
		}

		String summary = HtmlUtil.extractText(
			StringUtil.shorten(entry.getContent(), 500));

		AssetEntry assetEntry = assetEntryLocalService.updateEntry(
			userId, entry.getGroupId(), BlogsEntry.class.getName(),
			entry.getEntryId(), entry.getUuid(), 0, assetCategoryIds,
			assetTagNames, visible, null, null, entry.getDisplayDate(), null,
			ContentTypes.TEXT_HTML, entry.getTitle(), null, summary, null, null,
			0, 0, null, false);

		assetLinkLocalService.updateLinks(
			userId, assetEntry.getEntryId(), assetLinkEntryIds,
			AssetLinkConstants.TYPE_RELATED);
	}

	public BlogsEntry updateEntry(
			long userId, long entryId, String title, String description,
			String content, int displayDateMonth, int displayDateDay,
			int displayDateYear, int displayDateHour, int displayDateMinute,
			boolean allowPingbacks, boolean allowTrackbacks,
			String[] trackbacks, boolean smallImage, String smallImageURL,
			String smallImageFileName, InputStream smallImageInputStream,
			ServiceContext serviceContext)
		throws PortalException, SystemException {

		// Entry

		User user = userPersistence.findByPrimaryKey(userId);

		Date displayDate = PortalUtil.getDate(
			displayDateMonth, displayDateDay, displayDateYear, displayDateHour,
			displayDateMinute, user.getTimeZone(),
			new EntryDisplayDateException());

		byte[] smallImageBytes = null;

		try {
			if ((smallImageInputStream != null) && smallImage) {
				smallImageBytes = FileUtil.getBytes(smallImageInputStream);
			}
		}
		catch (IOException ioe) {
		}

		validate(
			title, content, smallImage, smallImageURL, smallImageFileName,
			smallImageBytes);

		BlogsEntry entry = blogsEntryPersistence.findByPrimaryKey(entryId);

		String oldUrlTitle = entry.getUrlTitle();

		entry.setModifiedDate(serviceContext.getModifiedDate(null));
		entry.setTitle(title);
		entry.setUrlTitle(
			getUniqueUrlTitle(entryId, title, oldUrlTitle, serviceContext));
		entry.setDescription(description);
		entry.setContent(content);
		entry.setDisplayDate(displayDate);
		entry.setAllowPingbacks(allowPingbacks);
		entry.setAllowTrackbacks(allowTrackbacks);
		entry.setSmallImage(smallImage);

		if (entry.getSmallImageId() == 0) {
			entry.setSmallImageId(counterLocalService.increment());
		}

		entry.setSmallImageURL(smallImageURL);

		if (entry.isPending() || entry.isDraft()) {
		}
		else if (entry.isApproved()) {
			entry.setStatus(WorkflowConstants.STATUS_DRAFT_FROM_APPROVED);
		}
		else {
			entry.setStatus(WorkflowConstants.STATUS_DRAFT);
		}

		entry.setExpandoBridgeAttributes(serviceContext);

		blogsEntryPersistence.update(entry, false);

		// Resources

		if ((serviceContext.getGroupPermissions() != null) ||
			(serviceContext.getGuestPermissions() != null)) {

			updateEntryResources(
				entry, serviceContext.getGroupPermissions(),
				serviceContext.getGuestPermissions());
		}

		// Small image

		saveImages(smallImage, entry.getSmallImageId(), smallImageBytes);

		// Asset

		updateAsset(
			userId, entry, serviceContext.getAssetCategoryIds(),
			serviceContext.getAssetTagNames(),
			serviceContext.getAssetLinkEntryIds());

		// Workflow

		boolean pingOldTrackbacks = false;

		if (!oldUrlTitle.equals(entry.getUrlTitle())) {
			pingOldTrackbacks = true;
		}

		serviceContext.setAttribute(
			"pingOldTrackbacks", String.valueOf(pingOldTrackbacks));

		if (entry.getStatus() != WorkflowConstants.STATUS_DRAFT) {
			serviceContext.setAttribute("update", Boolean.TRUE.toString());
		}

		if (Validator.isNotNull(trackbacks)) {
			serviceContext.setAttribute("trackbacks", trackbacks);
		}
		else {
			serviceContext.setAttribute("trackbacks", null);
		}

		WorkflowHandlerRegistryUtil.startWorkflowInstance(
			user.getCompanyId(), entry.getGroupId(), userId,
			BlogsEntry.class.getName(), entry.getEntryId(), entry,
			serviceContext);

		return entry;
	}

	public void updateEntryResources(
			BlogsEntry entry, String[] groupPermissions,
			String[] guestPermissions)
		throws PortalException, SystemException {

		resourceLocalService.updateResources(
			entry.getCompanyId(), entry.getGroupId(),
			BlogsEntry.class.getName(), entry.getEntryId(), groupPermissions,
			guestPermissions);
	}

	public BlogsEntry updateStatus(
			long userId, long entryId, int status,
			ServiceContext serviceContext)
		throws PortalException, SystemException {

		// Entry

		User user = userPersistence.findByPrimaryKey(userId);
		Date now = new Date();

		BlogsEntry entry = blogsEntryPersistence.findByPrimaryKey(entryId);

		int oldStatus = entry.getStatus();

		entry.setModifiedDate(serviceContext.getModifiedDate(now));
		entry.setStatus(status);
		entry.setStatusByUserId(user.getUserId());
		entry.setStatusByUserName(user.getFullName());
		entry.setStatusDate(serviceContext.getModifiedDate(now));

		blogsEntryPersistence.update(entry, false);

		Indexer indexer = IndexerRegistryUtil.nullSafeGetIndexer(
			BlogsEntry.class);

		if (status == WorkflowConstants.STATUS_APPROVED) {

			// Statistics

			blogsStatsUserLocalService.updateStatsUser(
				entry.getGroupId(), user.getUserId(), entry.getDisplayDate());

			if (oldStatus != WorkflowConstants.STATUS_APPROVED) {

				// Asset

				assetEntryLocalService.updateVisible(
					BlogsEntry.class.getName(), entryId, true);

				// Social

				if (oldStatus != WorkflowConstants.STATUS_IN_TRASH) {
					boolean update = ParamUtil.getBoolean(
						serviceContext, "update");

					if (update) {
						socialActivityLocalService.addActivity(
							user.getUserId(), entry.getGroupId(),
							BlogsEntry.class.getName(), entryId,
							BlogsActivityKeys.UPDATE_ENTRY, StringPool.BLANK,
							0);
					}
					else {
						socialActivityLocalService.addUniqueActivity(
							user.getUserId(), entry.getGroupId(),
							BlogsEntry.class.getName(), entryId,
							BlogsActivityKeys.ADD_ENTRY, StringPool.BLANK, 0);
					}
				}
			}

			// Indexer

			indexer.reindex(entry);

			if (oldStatus != WorkflowConstants.STATUS_IN_TRASH) {

				// Subscriptions

				notifySubscribers(entry, serviceContext);

				// Ping

				String[] trackbacks = (String[])serviceContext.getAttribute(
					"trackbacks");
				Boolean pingOldTrackbacks = ParamUtil.getBoolean(
					serviceContext, "pingOldTrackbacks");

				pingGoogle(entry, serviceContext);
				pingPingback(entry, serviceContext);
				pingTrackbacks(
					entry, trackbacks, pingOldTrackbacks, serviceContext);
			}
		}
		else {

			// Asset

			if (status == WorkflowConstants.STATUS_IN_TRASH) {
				assetEntryLocalService.moveEntryToTrash(
					BlogsEntry.class.getName(), entryId);
			}
			else {
				assetEntryLocalService.updateVisible(
					BlogsEntry.class.getName(), entryId, false);
			}

			// Indexer

			indexer.delete(entry);
		}

		return entry;
	}

	protected String getUniqueUrlTitle(long entryId, long groupId, String title)
		throws SystemException {

		String urlTitle = BlogsUtil.getUrlTitle(entryId, title);

		for (int i = 1;; i++) {
			BlogsEntry entry = blogsEntryPersistence.fetchByG_UT(
				groupId, urlTitle);

			if ((entry == null) || (entryId == entry.getEntryId())) {
				break;
			}
			else {
				String suffix = StringPool.DASH + i;

				String prefix = urlTitle;

				if (urlTitle.length() > suffix.length()) {
					prefix = urlTitle.substring(
						0, urlTitle.length() - suffix.length());
				}

				urlTitle = prefix + suffix;
			}
		}

		return urlTitle;
	}

	protected String getUniqueUrlTitle(
			long entryId, String title, String oldUrlTitle,
			ServiceContext serviceContext)
		throws SystemException {

		String serviceContextUrlTitle = ParamUtil.getString(
			serviceContext, "urlTitle");

		String urlTitle = null;

		if (isMatchesServiceContextUrlTitle(serviceContextUrlTitle)) {
			urlTitle = BlogsUtil.getUrlTitle(entryId, serviceContextUrlTitle);

			BlogsEntry urlTitleEntry = blogsEntryPersistence.fetchByG_UT(
				serviceContext.getScopeGroupId(), urlTitle);

			if ((urlTitleEntry != null) &&
				(urlTitleEntry.getEntryId() != entryId)) {

				urlTitle = getUniqueUrlTitle(
					entryId, serviceContext.getScopeGroupId(), urlTitle);
			}
		}
		else {
			if (isMatchesServiceContextUrlTitle(oldUrlTitle)) {
				urlTitle = oldUrlTitle;
			}
			else {
				urlTitle = getUniqueUrlTitle(
					entryId, serviceContext.getScopeGroupId(), title);
			}
		}

		return urlTitle;
	}

	protected boolean isMatchesServiceContextUrlTitle(String urlTitle) {
		if (Validator.isNotNull(urlTitle) &&
			Validator.isNotNull(PropsValues.BLOGS_ENTRY_URL_TITLE_REGEXP)) {

			Pattern pattern = Pattern.compile(
				PropsValues.BLOGS_ENTRY_URL_TITLE_REGEXP);

			Matcher matcher = pattern.matcher(urlTitle);

			return matcher.matches();
		}

		return false;
	}

	protected void notifySubscribers(
			BlogsEntry entry, ServiceContext serviceContext)
		throws SystemException {

		if (!entry.isApproved()) {
			return;
		}

		String layoutFullURL = serviceContext.getLayoutFullURL();

		if (Validator.isNull(layoutFullURL)) {
			return;
		}

		PortletPreferences preferences =
			ServiceContextUtil.getPortletPreferences(serviceContext);

		if (preferences == null) {
			long ownerId = entry.getGroupId();
			int ownerType = PortletKeys.PREFS_OWNER_TYPE_GROUP;
			long plid = PortletKeys.PREFS_PLID_SHARED;
			String portletId = PortletKeys.BLOGS;
			String defaultPreferences = null;

			preferences = portletPreferencesLocalService.getPreferences(
				entry.getCompanyId(), ownerId, ownerType, plid, portletId,
				defaultPreferences);
		}

		if (serviceContext.isCommandAdd() &&
			BlogsUtil.getEmailEntryAddedEnabled(preferences)) {
		}
		else if (serviceContext.isCommandUpdate() &&
				 BlogsUtil.getEmailEntryUpdatedEnabled(preferences)) {
		}
		else {
			return;
		}

		String entryURL =
			layoutFullURL + Portal.FRIENDLY_URL_SEPARATOR + "blogs" +
				StringPool.SLASH + entry.getEntryId();

		String fromName = BlogsUtil.getEmailFromName(
			preferences, entry.getCompanyId());
		String fromAddress = BlogsUtil.getEmailFromAddress(
			preferences, entry.getCompanyId());

		Map<Locale, String> localizedSubjectMap = null;
		Map<Locale, String> localizedBodyMap = null;

		if (serviceContext.isCommandUpdate()) {
			localizedSubjectMap = BlogsUtil.getEmailEntryUpdatedSubjectMap(
				preferences);
			localizedBodyMap = BlogsUtil.getEmailEntryUpdatedBodyMap(
				preferences);
		}
		else {
			localizedSubjectMap = BlogsUtil.getEmailEntryAddedSubjectMap(
				preferences);
			localizedBodyMap = BlogsUtil.getEmailEntryAddedBodyMap(preferences);
		}

		SubscriptionSender subscriptionSender = new SubscriptionSender();

		subscriptionSender.setCompanyId(entry.getCompanyId());
		subscriptionSender.setContextAttributes(
			"[$BLOGS_ENTRY_STATUS_BY_USER_NAME$]", entry.getStatusByUserName(),
			"[$BLOGS_ENTRY_URL$]", entryURL);
		subscriptionSender.setContextUserPrefix("BLOGS_ENTRY");
		subscriptionSender.setFrom(fromAddress, fromName);
		subscriptionSender.setHtmlFormat(true);
		subscriptionSender.setLocalizedBodyMap(localizedBodyMap);
		subscriptionSender.setLocalizedSubjectMap(localizedSubjectMap);
		subscriptionSender.setMailId("blogs_entry", entry.getEntryId());
		subscriptionSender.setPortletId(PortletKeys.BLOGS);
		subscriptionSender.setReplyToAddress(fromAddress);
		subscriptionSender.setScopeGroupId(entry.getGroupId());
		subscriptionSender.setServiceContext(serviceContext);
		subscriptionSender.setUserId(entry.getUserId());

		subscriptionSender.addPersistedSubscribers(
			BlogsEntry.class.getName(), entry.getGroupId());

		subscriptionSender.flushNotificationsAsync();
	}

	protected void pingGoogle(BlogsEntry entry, ServiceContext serviceContext)
		throws PortalException, SystemException {

		if (!PropsValues.BLOGS_PING_GOOGLE_ENABLED || !entry.isApproved()) {
			return;
		}

		String layoutFullURL = PortalUtil.getLayoutFullURL(
			serviceContext.getScopeGroupId(), PortletKeys.BLOGS);

		if (Validator.isNull(layoutFullURL)) {
			return;
		}

		if (layoutFullURL.contains("://localhost")) {
			if (_log.isDebugEnabled()) {
				_log.debug(
					"Not pinging Google because of localhost URL " +
						layoutFullURL);
			}

			return;
		}

		Group group = groupPersistence.findByPrimaryKey(entry.getGroupId());

		StringBundler sb = new StringBundler(6);

		String name = group.getDescriptiveName();
		String url = layoutFullURL + Portal.FRIENDLY_URL_SEPARATOR + "blogs";
		String changesURL =
			layoutFullURL + Portal.FRIENDLY_URL_SEPARATOR + "blogs/rss";

		sb.append("http://blogsearch.google.com/ping?name=");
		sb.append(HttpUtil.encodeURL(name));
		sb.append("&url=");
		sb.append(HttpUtil.encodeURL(url));
		sb.append("&changesURL=");
		sb.append(HttpUtil.encodeURL(changesURL));

		String location = sb.toString();

		if (_log.isInfoEnabled()) {
			_log.info("Pinging Google at " + location);
		}

		try {
			String response = HttpUtil.URLtoString(sb.toString());

			if (_log.isInfoEnabled()) {
				_log.info("Google ping response: " + response);
			}
		}
		catch (IOException ioe) {
			_log.error("Unable to ping Google at " + location, ioe);
		}
	}

	protected void pingPingback(
		BlogsEntry entry, ServiceContext serviceContext) {

		if (!PropsValues.BLOGS_PINGBACK_ENABLED ||
			!entry.isAllowPingbacks() || !entry.isApproved()) {

			return;
		}

		String layoutFullURL = serviceContext.getLayoutFullURL();

		if (Validator.isNull(layoutFullURL)) {
			return;
		}

		String sourceUri =
			layoutFullURL + Portal.FRIENDLY_URL_SEPARATOR + "blogs/" +
				entry.getUrlTitle();

		Source source = new Source(entry.getContent());

		List<StartTag> tags = source.getAllStartTags("a");

		for (StartTag tag : tags) {
			String targetUri = tag.getAttributeValue("href");

			if (Validator.isNotNull(targetUri)) {
				try {
					LinkbackProducerUtil.sendPingback(sourceUri, targetUri);
				}
				catch (Exception e) {
					_log.error("Error while sending pingback " + targetUri, e);
				}
			}
		}
	}

	protected void pingTrackbacks(
			BlogsEntry entry, String[] trackbacks, boolean pingOldTrackbacks,
			ServiceContext serviceContext)
		throws SystemException {

		if (!PropsValues.BLOGS_TRACKBACK_ENABLED ||
			!entry.isAllowTrackbacks() || !entry.isApproved()) {

			return;
		}

		String layoutFullURL = serviceContext.getLayoutFullURL();

		if (Validator.isNull(layoutFullURL)) {
			return;
		}

		Map<String, String> parts = new HashMap<String, String>();

		String excerpt = StringUtil.shorten(
			HtmlUtil.extractText(entry.getContent()),
			PropsValues.BLOGS_LINKBACK_EXCERPT_LENGTH);
		String url =
			layoutFullURL + Portal.FRIENDLY_URL_SEPARATOR + "blogs/" +
				entry.getUrlTitle();

		parts.put("title", entry.getTitle());
		parts.put("excerpt", excerpt);
		parts.put("url", url);
		parts.put("blog_name", entry.getUserName());

		Set<String> trackbacksSet = null;

		if (Validator.isNotNull(trackbacks)) {
			trackbacksSet = SetUtil.fromArray(trackbacks);
		}
		else {
			trackbacksSet = new HashSet<String>();
		}

		if (pingOldTrackbacks) {
			trackbacksSet.addAll(
				SetUtil.fromArray(StringUtil.split(entry.getTrackbacks())));

			entry.setTrackbacks(StringPool.BLANK);

			blogsEntryPersistence.update(entry, false);
		}

		Set<String> oldTrackbacks = SetUtil.fromArray(
			StringUtil.split(entry.getTrackbacks()));

		Set<String> validTrackbacks = new HashSet<String>();

		for (String trackback : trackbacksSet) {
			if (oldTrackbacks.contains(trackback)) {
				continue;
			}

			try {
				if (LinkbackProducerUtil.sendTrackback(trackback, parts)) {
					validTrackbacks.add(trackback);
				}
			}
			catch (Exception e) {
				_log.error("Error while sending trackback at " + trackback, e);
			}
		}

		if (!validTrackbacks.isEmpty()) {
			String newTrackbacks = StringUtil.merge(validTrackbacks);

			if (Validator.isNotNull(entry.getTrackbacks())) {
				newTrackbacks += StringPool.COMMA + entry.getTrackbacks();
			}

			entry.setTrackbacks(newTrackbacks);

			blogsEntryPersistence.update(entry, false);
		}
	}

	protected void saveImages(
			boolean smallImage, long smallImageId, byte[] smallImageBytes)
		throws PortalException, SystemException {

		if (smallImage) {
			if (smallImageBytes != null) {
				imageLocalService.updateImage(smallImageId, smallImageBytes);
			}
		}
		else {
			imageLocalService.deleteImage(smallImageId);
		}
	}

	protected void validate(
			String title, String content, boolean smallImage,
			String smallImageURL, String smallImageFileName,
			byte[] smallImageBytes)
		throws PortalException, SystemException {

		if (Validator.isNull(title)) {
			throw new EntryTitleException();
		}
		else if (Validator.isNull(content)) {
			throw new EntryContentException();
		}

		String[] imageExtensions = PrefsPropsUtil.getStringArray(
			PropsKeys.BLOGS_IMAGE_EXTENSIONS, StringPool.COMMA);

		if (smallImage && Validator.isNull(smallImageURL) &&
			(smallImageBytes != null)) {

			if (smallImageFileName != null) {
				boolean validSmallImageExtension = false;

				for (String _imageExtension : imageExtensions) {
					if (StringPool.STAR.equals(_imageExtension) ||
						StringUtil.endsWith(
							smallImageFileName, _imageExtension)) {

						validSmallImageExtension = true;

						break;
					}
				}

				if (!validSmallImageExtension) {
					throw new EntrySmallImageNameException(smallImageFileName);
				}
			}

			long smallImageMaxSize = PrefsPropsUtil.getLong(
				PropsKeys.BLOGS_IMAGE_SMALL_MAX_SIZE);

			if ((smallImageMaxSize > 0) &&
				(smallImageBytes.length > smallImageMaxSize)) {

				throw new EntrySmallImageSizeException();
			}
		}
	}

	private static Log _log = LogFactoryUtil.getLog(
		BlogsEntryLocalServiceImpl.class);

}