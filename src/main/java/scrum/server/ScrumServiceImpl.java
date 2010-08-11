package scrum.server;

import ilarkesto.auth.Auth;
import ilarkesto.auth.WrongPasswordException;
import ilarkesto.base.PermissionDeniedException;
import ilarkesto.base.Reflect;
import ilarkesto.base.Str;
import ilarkesto.base.Utl;
import ilarkesto.base.time.Date;
import ilarkesto.base.time.DateAndTime;
import ilarkesto.core.logging.Log;
import ilarkesto.gwt.server.AGwtConversation;
import ilarkesto.persistence.ADao;
import ilarkesto.persistence.AEntity;
import ilarkesto.persistence.EntityDoesNotExistException;
import ilarkesto.webapp.AWebApplication;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import scrum.client.DataTransferObject;
import scrum.client.admin.SystemMessage;
import scrum.server.admin.User;
import scrum.server.admin.UserDao;
import scrum.server.collaboration.ChatMessage;
import scrum.server.collaboration.Comment;
import scrum.server.collaboration.CommentDao;
import scrum.server.collaboration.EmoticonDao;
import scrum.server.collaboration.Wikipage;
import scrum.server.common.Numbered;
import scrum.server.common.Transient;
import scrum.server.files.File;
import scrum.server.impediments.Impediment;
import scrum.server.issues.Issue;
import scrum.server.journal.Change;
import scrum.server.journal.ChangeDao;
import scrum.server.journal.ProjectEvent;
import scrum.server.journal.ProjectEventDao;
import scrum.server.pr.BlogEntry;
import scrum.server.pr.BlogEntryDao;
import scrum.server.project.HomepageUpdater;
import scrum.server.project.Project;
import scrum.server.project.ProjectDao;
import scrum.server.project.Requirement;
import scrum.server.project.RequirementDao;
import scrum.server.release.Release;
import scrum.server.release.ReleaseDao;
import scrum.server.risks.Risk;
import scrum.server.sprint.Sprint;
import scrum.server.sprint.Task;

public class ScrumServiceImpl extends GScrumServiceImpl {

	private static final Log LOG = Log.get(ScrumServiceImpl.class);
	private static final long serialVersionUID = 1;

	// --- dependencies ---

	private transient ProjectDao projectDao;
	private transient UserDao userDao;
	private transient RequirementDao requirementDao;
	private transient ReleaseDao releaseDao;
	private transient CommentDao commentDao;
	private transient ScrumWebApplication webApplication;
	private transient ProjectEventDao projectEventDao;
	private transient EmoticonDao emoticonDao;
	private transient ChangeDao changeDao;
	private transient BlogEntryDao blogEntryDao;

	public void setReleaseDao(ReleaseDao releaseDao) {
		this.releaseDao = releaseDao;
	}

	public void setChangeDao(ChangeDao changeDao) {
		this.changeDao = changeDao;
	}

	public void setEmoticonDao(EmoticonDao emoticonDao) {
		this.emoticonDao = emoticonDao;
	}

	public void setProjectEventDao(ProjectEventDao projectEventDao) {
		this.projectEventDao = projectEventDao;
	}

	public void setWebApplication(ScrumWebApplication webApplication) {
		this.webApplication = webApplication;
	}

	public void setRequirementDao(RequirementDao requirementDao) {
		this.requirementDao = requirementDao;
	}

	public void setProjectDao(ProjectDao projectDao) {
		this.projectDao = projectDao;
	}

	public void setUserDao(UserDao userDao) {
		this.userDao = userDao;
	}

	public void setCommentDao(CommentDao commentDao) {
		this.commentDao = commentDao;
	}

	public void setBlogEntryDao(BlogEntryDao blogEntryDao) {
		this.blogEntryDao = blogEntryDao;
	}

	// --- ---

	private void onStartConversation(GwtConversation conversation) {
		conversation.clearRemoteEntities();
		conversation.getNextData().applicationInfo = webApplication.getApplicationInfo();
		conversation.sendToClient(webApplication.getSystemConfig());
	}

	@Override
	public void onLogin(GwtConversation conversation, String username, String password) {
		username = username.toLowerCase();
		conversation.clearRemoteEntities();
		User user = null;
		if (username.contains("@")) {
			user = userDao.getUserByEmail(username);
		}
		if (user == null) {
			user = userDao.getUserByName(username);
		}

		if (user == null || user.matchesPassword(password) == false) {
			conversation.getNextData().addError("Login failed.");
			return;
		}

		if (user.isDisabled()) {
			conversation.getNextData().addError("User is disabled.");
			return;
		}

		user.setLastLoginDateAndTime(DateAndTime.now());
		conversation.getSession().setUser(user);
		conversation.sendUserScopeDataToClient(user);
	}

	@Override
	public void onCreateExampleProject(GwtConversation conversation) {
		User user = conversation.getSession().getUser();
		Project project = projectDao.postExampleProject(user, user, user);
		conversation.sendToClient(project);
	}

	@Override
	public void onSearch(GwtConversation conversation, String text) {
		Project project = conversation.getProject();
		if (project == null) return;
		List<AEntity> foundEntities = project.search(text);
		LOG.debug("Found entities for search", "\"" + text + "\"", "->", foundEntities);
		conversation.sendToClient(foundEntities);
	}

	@Override
	public void onUpdateSystemMessage(GwtConversation conversation, SystemMessage systemMessage) {
		User user = conversation.getSession().getUser();
		if (user == null || user.isAdmin() == false) throw new PermissionDeniedException();
		webApplication.updateSystemMessage(systemMessage);
	}

	@Override
	public void onSetSelectedEntitysIds(GwtConversation conversation, Set ids) {
		Project project = conversation.getProject();
		if (project == null) return;
		webApplication.setUsersSelectedEntities(project, conversation, ids);
	}

	@Override
	public void onLogout(GwtConversation conversation) {
		WebSession session = conversation.getSession();
		webApplication.destroyWebSession(session, getThreadLocalRequest().getSession());
	}

	@Override
	public void onResetPassword(GwtConversation conversation, String userId) {
		User user = userDao.getById(userId);
		if (user.isEmailSet()) {
			if (!user.isEmailVerified()) user.triggerEmailVerification();
			user.triggerPasswordReset();
		} else {
			user.setPassword(scrum.client.admin.User.INITIAL_PASSWORD);
		}
	}

	@Override
	public void onRequestNewPassword(GwtConversation conversation, String login) {
		login = login.toLowerCase();
		User user = null;
		if (login.contains("@")) {
			user = userDao.getUserByEmail(login);
		}
		if (user == null) {
			user = userDao.getUserByName(login);
		}

		if (user == null) {
			conversation.getNextData().addError("User '" + login + "' does not exist.");
			return;
		}

		if (user.isAdmin()) {
			conversation.getNextData().addError("Admins can not request new passwords.");
			return;
		}

		if (!user.isEmailVerified()) {
			conversation.getNextData().addError(
				"User '" + login + "' has no verified email. Please contact the admin: "
						+ webApplication.getSystemConfig().getAdminEmail());
			return;
		}

		user.triggerNewPasswordRequest();
	}

	@Override
	public void onChangePassword(GwtConversation conversation, String oldPassword, String newPassword) {
		User user = conversation.getSession().getUser();
		if (user.matchesPassword(oldPassword) == false) throw new WrongPasswordException();

		user.setPassword(newPassword);

		LOG.info("password changed by user");
	}

	@Override
	public void onCreateEntity(GwtConversation conversation, String type, Map properties) {
		String id = (String) properties.get("id");
		if (id == null) throw new NullPointerException("id == null");

		ADao dao = getDaoService().getDaoByName(type);
		AEntity entity = dao.newEntityInstance(id);
		entity.updateProperties(properties);
		User currentUser = conversation.getSession().getUser();

		if (entity instanceof Numbered) {
			((Numbered) entity).updateNumber();
		}

		if (entity instanceof Project) {
			Project project = (Project) entity;
			project.addParticipant(currentUser);
			project.addAdmin(currentUser);
			project.addProductOwner(currentUser);
			project.addScrumMaster(currentUser);
			project.addTeamMember(currentUser);
		}

		if (entity instanceof Comment) {
			Comment comment = (Comment) entity;
			comment.setDateAndTime(DateAndTime.now());
			postProjectEvent(conversation, comment.getAuthor().getName() + " commented on " + comment.getParent());
		}

		if (entity instanceof ChatMessage) {
			ChatMessage chatMessage = (ChatMessage) entity;
			chatMessage.setDateAndTime(DateAndTime.now());
		}

		if (entity instanceof Impediment) {
			Impediment impediment = (Impediment) entity;
			impediment.setDate(Date.today());
		}

		if (entity instanceof Issue) {
			Issue issue = (Issue) entity;
			issue.setDate(DateAndTime.now());
			issue.setCreator(currentUser);
		}

		if (entity instanceof Task) {
			Task task = (Task) entity;
			task.getRequirement().setRejectDate(null);
		}

		if (entity instanceof BlogEntry) {
			BlogEntry blogEntry = (BlogEntry) entity;
			blogEntry.setDateAndTime(DateAndTime.now());
			blogEntry.addAuthor(currentUser);
		}

		if (!(entity instanceof Transient)) dao.saveEntity(entity);

		sendToClients(conversation, entity);

		if (entity instanceof Task || entity instanceof Requirement || entity instanceof Wikipage
				|| entity instanceof Risk || entity instanceof Impediment || entity instanceof Issue
				|| entity instanceof BlogEntry) {
			User user = currentUser;
			Change change = changeDao.postChange(entity, user, "@created", null, null);
			conversation.sendToClient(change);
		}
	}

	@Override
	public void onDeleteEntity(GwtConversation conversation, String entityId) {
		AEntity entity = getDaoService().getEntityById(entityId);
		if (!Auth.isDeletable(entity, conversation.getSession().getUser())) throw new PermissionDeniedException();

		if (entity instanceof File) {
			File file = (File) entity;
			file.deleteFile();
		}

		ADao dao = getDaoService().getDao(entity);
		dao.deleteEntity(entity);

		Project project = conversation.getProject();
		if (project != null) {
			for (GwtConversation c : webApplication.getConversationsByProject(project, conversation)) {
				c.getNextData().addDeletedEntity(entityId);
			}
		}
	}

	@Override
	public void onChangeProperties(GwtConversation conversation, String entityId, Map properties) {
		AEntity entity = getDaoService().getEntityById(entityId);
		User currentUser = conversation.getSession().getUser();
		if (!Auth.isEditable(entity, currentUser)) throw new PermissionDeniedException();

		if (entity instanceof Task) {
			// update sprint day snapshot before change
			Task task = (Task) entity;
			task.getRequirement().getSprint().getDaySnapshot(Date.today()).updateWithCurrentSprint();
		}

		Sprint previousRequirementSprint = null;
		if (entity instanceof Requirement) {
			Requirement requirement = (Requirement) entity;
			previousRequirementSprint = requirement.getSprint();

			postChangeIfChanged(conversation, entity, properties, currentUser, "description");
			postChangeIfChanged(conversation, entity, properties, currentUser, "testDescription");
			postChangeIfChanged(conversation, entity, properties, currentUser, "sprintId");
			postChangeIfChanged(conversation, entity, properties, currentUser, "closed");
		}
		if (entity instanceof Wikipage) {
			postChangeIfChanged(conversation, entity, properties, currentUser, "text");
		}
		if (entity instanceof Risk) {
			postChangeIfChanged(conversation, entity, properties, currentUser, "description");
			postChangeIfChanged(conversation, entity, properties, currentUser, "probability");
			postChangeIfChanged(conversation, entity, properties, currentUser, "impact");
			postChangeIfChanged(conversation, entity, properties, currentUser, "probabilityMitigation");
			postChangeIfChanged(conversation, entity, properties, currentUser, "impactMitigation");
		}
		if (entity instanceof Impediment) {
			postChangeIfChanged(conversation, entity, properties, currentUser, "description");
			postChangeIfChanged(conversation, entity, properties, currentUser, "solution");
			postChangeIfChanged(conversation, entity, properties, currentUser, "closed");
		}
		if (entity instanceof Issue) {
			postChangeIfChanged(conversation, entity, properties, currentUser, "description");
			postChangeIfChanged(conversation, entity, properties, currentUser, "statement");
			postChangeIfChanged(conversation, entity, properties, currentUser, "closeDate");
		}
		if (entity instanceof BlogEntry) {
			postChangeIfChanged(conversation, entity, properties, currentUser, "text");
		}

		entity.updateProperties(properties);

		if (entity instanceof Task) {
			// update sprint day snapshot after change
			Task task = (Task) entity;
			Requirement requirement = task.getRequirement();
			requirement.getSprint().getDaySnapshot(Date.today()).updateWithCurrentSprint();

			if (task.isClosed() && properties.containsKey("remainingWork")) {
				String event = currentUser.getName() + " closed " + task.getReferenceAndLabel();
				if (requirement.isTasksClosed()) {
					event += ", all tasks closed in " + requirement.getReferenceAndLabel();
				}
				postProjectEvent(conversation, event);
			} else if (task.isOwnerSet() && properties.containsKey("ownerId")) {
				postProjectEvent(conversation, currentUser.getName() + " claimed " + task.getReferenceAndLabel());
			}
			if (!task.isOwnerSet() && properties.containsKey("ownerId")) {
				postProjectEvent(conversation, currentUser.getName() + " unclaimed " + task.getReferenceAndLabel());
			}
			if (!task.isClosed() && requirement.isRejectDateSet()) {
				requirement.setRejectDate(null);
				sendToClients(conversation, requirement);
			}
		}

		if (entity instanceof Requirement) {
			Requirement requirement = (Requirement) entity;
			Sprint sprint = requirement.getSprint();
			boolean inCurrentSprint = sprint != null && conversation.getProject().isCurrentSprint(sprint);

			if (properties.containsKey("description") || properties.containsKey("testDescription")
					|| properties.containsKey("qualitysIds")) {
				requirement.setDirty(true);
				conversation.sendToClient(requirement);
			}

			if (properties.containsKey("rejectDate") && requirement.isRejectDateSet()) {
				postProjectEvent(conversation,
					currentUser.getName() + " rejected " + requirement.getReferenceAndLabel());
			}

			if (properties.containsKey("accepted") && requirement.isRejectDateSet()) {
				postProjectEvent(conversation,
					currentUser.getName() + " accepted " + requirement.getReferenceAndLabel());
			}

			if (sprint != previousRequirementSprint) {
				if (properties.containsKey("sprintId")) {
					if (inCurrentSprint) {
						postProjectEvent(conversation,
							currentUser.getName() + " pulled " + requirement.getReferenceAndLabel()
									+ " to current sprint");
					} else {
						postProjectEvent(conversation,
							currentUser.getName() + " kicked " + requirement.getReferenceAndLabel()
									+ " from current sprint");
					}
				}
			}

			if (properties.containsKey("estimatedWork")) {
				requirement.initializeEstimationVotes();
				requirement.setDirty(false);
				requirement.setWorkEstimationVotingShowoff(false);
				requirement.setWorkEstimationVotingActive(false);
				conversation.sendToClient(requirement);
			}

			requirement.getProject().getCurrentSprintSnapshot().update();
		}

		if (entity instanceof Project) {
			Project project = (Project) entity;
		}

		if (entity instanceof Impediment) {
			Impediment impediment = (Impediment) entity;
			if (impediment.isClosed() && properties.containsKey("closed")) {
				postProjectEvent(conversation, currentUser.getName() + " closed " + impediment.getReferenceAndLabel());
			}
		}

		if (entity instanceof Issue) {
			Issue issue = (Issue) entity;

			if (properties.containsKey("closeDate")) {
				if (issue.isClosed()) {
					issue.setCloseDate(Date.today());
					postProjectEvent(conversation, currentUser.getName() + " closed " + issue.getReferenceAndLabel());
				} else {
					postProjectEvent(conversation, currentUser.getName() + " reopened " + issue.getReferenceAndLabel());
				}
			}

			if (properties.containsKey("ownerId") && issue.isOwnerSet()) {
				postProjectEvent(conversation, currentUser.getName() + " claimed " + issue.getReferenceAndLabel());
			}

			if (properties.containsKey("fixDate")) {
				if (issue.isFixed()) {
					postProjectEvent(conversation, currentUser.getName() + " fixed " + issue.getReferenceAndLabel());
				} else {
					postProjectEvent(conversation,
						currentUser.getName() + " rejected fix for " + issue.getReferenceAndLabel());
				}
			}
		}

		if (entity instanceof BlogEntry) {
			BlogEntry blogEntry = (BlogEntry) entity;

			if (properties.containsKey("published") && blogEntry.isPublished()) {
				postProjectEvent(conversation, currentUser.getName() + " published " + blogEntry.getReferenceAndLabel());
			}

			if (properties.containsKey("text")) {
				blogEntry.addAuthor(currentUser);
			}
		}

		sendToOtherConversationsByProject(conversation, entity);
	}

	@Override
	public void onSelectProject(GwtConversation conversation, String projectId) {
		Project project = projectDao.getById(projectId);
		User user = conversation.getSession().getUser();
		if (!project.isVisibleFor(user))
			throw new PermissionDeniedException("Project '" + project + "' is not visible for user '" + user + "'");

		project.setLastOpenedDateAndTime(DateAndTime.now());
		conversation.setProject(project);
		user.setCurrentProject(project);

		conversation.sendToClient(project);
		conversation.sendToClient(project.getSprints());
		conversation.sendToClient(project.getParticipants());
		Set<Requirement> requirements = project.getRequirements();
		conversation.sendToClient(requirements);
		for (Requirement requirement : requirements) {
			conversation.sendToClient(requirement.getEstimationVotes());
		}
		conversation.sendToClient(project.getQualitys());
		conversation.sendToClient(project.getTasks());
		conversation.sendToClient(project.getUserConfigs());
		conversation.sendToClient(project.getWikipages());
		conversation.sendToClient(project.getImpediments());
		conversation.sendToClient(project.getRisks());
		conversation.sendToClient(project.getLatestProjectEvents());
		conversation.sendToClient(project.getCalendarEvents());
		conversation.sendToClient(project.getFiles());
		conversation.sendToClient(project.getOpenIssues());
		conversation.sendToClient(project.getReleases());
		conversation.sendToClient(project.getBlogEntrys());

		webApplication.updateOnlineTeamMembers(project, null);
	}

	@Override
	public void onCloseProject(GwtConversation conversation) {
		Project project = conversation.getProject();
		if (project != null) webApplication.setUsersSelectedEntities(project, conversation, new HashSet<String>(0));
		conversation.clearRemoteEntities();
		conversation.setProject(null);
		if (project != null) webApplication.updateOnlineTeamMembers(project, conversation);
	}

	@Override
	public void onRequestForum(GwtConversation conversation) {
		Project project = conversation.getProject();
		Set<AEntity> parents = new HashSet<AEntity>();
		parents.addAll(project.getSubjects());
		for (Comment comment : project.getLatestComments()) {
			conversation.sendToClient(comment);
			parents.add(comment.getParent());
		}
		conversation.sendToClient(parents);
	}

	@Override
	public void onRequestImpediments(GwtConversation conversation) {
		assertProjectSelected(conversation);
		Project project = conversation.getProject();
		conversation.sendToClient(project.getImpediments());
	}

	@Override
	public void onRequestRisks(GwtConversation conversation) {
		assertProjectSelected(conversation);
		Project project = conversation.getProject();
		conversation.sendToClient(project.getRisks());
	}

	@Override
	public void onRequestAcceptedIssues(GwtConversation conversation) {
		assertProjectSelected(conversation);
		Project project = conversation.getProject();
		conversation.sendToClient(project.getAcceptedIssues());
	}

	@Override
	public void onRequestClosedIssues(GwtConversation conversation) {
		assertProjectSelected(conversation);
		Project project = conversation.getProject();
		conversation.sendToClient(project.getClosedIssues());
	}

	@Override
	public void onRequestReleaseIssues(GwtConversation conversation, String releaseId) {
		assertProjectSelected(conversation);
		Project project = conversation.getProject();
		Release release = releaseDao.getById(releaseId);
		if (!release.isProject(project)) throw new PermissionDeniedException();
		conversation.sendToClient(release.getIssues());
	}

	@Override
	public void onRequestEntity(GwtConversation conversation, String entityId) {
		assertProjectSelected(conversation);

		try {
			AEntity entity = getDaoService().getById(entityId);
			if (!Auth.isVisible(entity, conversation.getSession().getUser())) throw new PermissionDeniedException();
			// TODO check if entity is from project
			conversation.sendToClient(entity);
		} catch (EntityDoesNotExistException ex) {
			// nop
		}
	}

	@Override
	public void onRequestEntityByReference(GwtConversation conversation, String reference) {
		assertProjectSelected(conversation);
		Project project = conversation.getProject();

		AEntity entity = project.getEntityByReference(reference);
		if (entity == null) {
			LOG.info("Requested entity not found:", reference);
		} else {
			conversation.sendToClient(entity);
		}
	}

	@Override
	public void onRequestComments(GwtConversation conversation, String parentId) {
		conversation.sendToClient(commentDao.getCommentsByParentId(parentId));
	}

	@Override
	public void onRequestChanges(GwtConversation conversation, String parentId) {
		conversation.sendToClient(changeDao.getChangesByParentId(parentId));
	}

	@Override
	public void onRequestRequirementEstimationVotes(GwtConversation conversation, String requirementId) {
		assertProjectSelected(conversation);
		Project project = conversation.getProject();
		Requirement requirement = requirementDao.getById(requirementId);
		if (!requirement.isProject(project)) throw new PermissionDeniedException();
		conversation.sendToClient(requirement.getEstimationVotes());
	}

	@Override
	public void onActivateRequirementEstimationVoting(GwtConversation conversation, String requirementId) {
		Requirement requirement = requirementDao.getById(requirementId);
		if (requirement == null || !requirement.isProject(conversation.getProject()))
			throw new PermissionDeniedException();
		requirement.initializeEstimationVotes();
		requirement.setWorkEstimationVotingActive(true);
		requirement.setWorkEstimationVotingShowoff(false);
		sendToClients(conversation, requirement);
		sendToClients(conversation, requirement.getEstimationVotes());
	}

	@Override
	public void onSwitchToNextSprint(GwtConversation conversation) {
		assertProjectSelected(conversation);
		Project project = conversation.getProject();
		Sprint oldSprint = project.getCurrentSprint();
		for (Requirement requirement : oldSprint.getRequirements()) {
			if (!requirement.isClosed()) {
				requirement.setDirty(true);
				sendToClients(conversation, requirement);
			}
		}
		project.switchToNextSprint();
		sendToClients(conversation, project.getSprints());
		sendToClients(conversation, project.getRequirements());
		sendToClients(conversation, project.getTasks());
		sendToClients(conversation, project);
	}

	@Override
	public void onUpdateProjectHomepage(GwtConversation conversation) {
		assertProjectSelected(conversation);
		Project project = conversation.getProject();
		HomepageUpdater.updateHomepage(project);
	}

	@Override
	public void onRegister(GwtConversation conversation, String username, String email, String password) {
		if (Str.isBlank(email)) email = null;

		if (Str.containsNonLetterOrDigit(username)) {
			conversation.getNextData().addError(
				"Registration failed. Name '" + username
						+ "' contains an illegal character. Only letters and digits allowed.");
			return;
		}
		if (userDao.getUserByName(username) != null) {
			conversation.getNextData().addError("Registration failed. Name '" + username + "' is already used.");
			log.warn("Registration failed. User name already exists:", username);
			return;
		}
		if (email != null && userDao.getUserByEmail(email) != null) {
			conversation.getNextData().addError("Registration failed. Email '" + email + "' is already used.");
			log.warn("Registration failed. User email already exists:", email);
			return;
		}

		User user = userDao.postUser(email, username, password);
		user.setLastLoginDateAndTime(DateAndTime.now());
		user.triggerEmailVerification();
		webApplication.triggerRegisterNotification(user);

		conversation.getSession().setUser(user);
		conversation.sendUserScopeDataToClient(user);
	}

	@Override
	public void onSendTestEmail(GwtConversation conversation) {
		webApplication.sendEmail(null, null, "Kunagi email test", "Kunagi email test");
	}

	@Override
	public void onPing(GwtConversation conversation) {
		// nop
	}

	@Override
	public void onSleep(GwtConversation conversation, long millis) {
		Utl.sleep(millis);
	}

	// --- helper ---

	@Override
	public DataTransferObject startConversation(int conversationNumber) {
		LOG.debug("startConversation");
		WebSession session = (WebSession) getSession();
		GwtConversation conversation = session.getGwtConversation(-1);
		ilarkesto.di.Context context = ilarkesto.di.Context.get();
		context.setName("gwt-srv:startSession");
		context.bindCurrentThread();
		try {
			onStartConversation(conversation);
		} catch (Throwable t) {
			handleServiceMethodException(conversation.getNumber(), "startSession", t);
			throw new RuntimeException(t);
		}
		scrum.client.DataTransferObject ret = (scrum.client.DataTransferObject) conversation.popNextData();
		onServiceMethodExecuted(context);
		return ret;
	}

	private void postChangeIfChanged(GwtConversation conversation, AEntity entity, Map properties, User user,
			String property) {
		if (properties.containsKey(property)) {
			boolean reference = property.endsWith("Id");
			Object oldValue = Reflect.getProperty(entity, property);
			Object newValue = properties.get(property);
			Change change = changeDao.postChange(entity, user, property, oldValue, newValue);
			conversation.sendToClient(change);
		}
	}

	private void postProjectEvent(GwtConversation conversation, String label) {
		assertProjectSelected(conversation);
		ProjectEvent event = projectEventDao.postEvent(conversation.getProject(), label);
		sendToClients(conversation, event);
		sendToClients(conversation, event.createChatMessage());
	}

	private void sendToClients(GwtConversation conversation, Collection<? extends AEntity> entities) {
		for (AEntity entity : entities) {
			sendToClients(conversation, entity);
		}
	}

	private void sendToClients(GwtConversation conversation, AEntity entity) {
		conversation.sendToClient(entity);
		sendToOtherConversationsByProject(conversation, entity);
	}

	private void sendToOtherConversationsByProject(GwtConversation conversation, AEntity entity) {
		for (AGwtConversation c : webApplication.getConversationsByProject(conversation.getProject(), conversation)) {
			c.sendToClient(entity);
		}
	}

	private void assertProjectSelected(GwtConversation conversation) {
		if (conversation.getProject() == null) throw new RuntimeException("No project selected.");
	}

	@Override
	protected Class<? extends AWebApplication> getWebApplicationClass() {
		return ScrumWebApplication.class;
	}

	@Override
	protected AWebApplication getWebApplication() {
		return webApplication;
	}

}
