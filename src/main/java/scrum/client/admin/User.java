package scrum.client.admin;

import ilarkesto.core.base.Str;
import ilarkesto.core.base.Utl;
import ilarkesto.core.scope.Scope;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import scrum.client.ScrumScopeManager;
import scrum.client.collaboration.UsersStatus;
import scrum.client.project.Project;

public class User extends GUser {

	public static final String INITIAL_NAME = "newuser";
	public static final String INITIAL_PASSWORD = "geheim";

	public User() {
		setName(getNextNewUserName());
	}

	public User(Map data) {
		super(data);
	}

	private String getNextNewUserName() {
		int index = 1;
		while (true) {
			String name = "newuser" + index;
			if (getDao().getUserByName(name) == null) return name;
			index++;
		}
	}

	public ProjectUserConfig getProjectConfig() {
		return ScrumScopeManager.getProject().getUserConfig(this);
	}

	public List<Project> getProjects() {
		List<Project> ret = new ArrayList<Project>();
		for (Project project : getDao().getProjects()) {
			if (project.isParticipant(this)) ret.add(project);
		}
		return ret;
	}

	public int compareTo(User u) {
		return getName().compareTo(u.getName());
	}

	@Override
	public String toString() {
		return getName();
	}

	@Override
	public EmailModel getEmailModel() {
		return new EmailModel() {

			@Override
			public void setValue(String value) {
				if (!Str.isEmail(value)) throw new RuntimeException("Invalid email.");
				super.setValue(value);
			}
		};
	}

	public static final Comparator<User> LAST_LOGIN_COMPARATOR = new Comparator<User>() {

		@Override
		public int compare(User a, User b) {
			return Utl.compare(b.getLastLoginDateAndTime(), a.getLastLoginDateAndTime());
		}
	};

	public static final Comparator<User> NAME_COMPARATOR = new Comparator<User>() {

		@Override
		public int compare(User a, User b) {
			return a.getName().compareTo(b.getName());
		}
	};

	public transient static final Comparator<User> ONLINE_OFFLINE_COMPARATOR = new Comparator<User>() {

		@Override
		public int compare(User a, User b) {
			UsersStatus usersStatus = Scope.get().getComponent(UsersStatus.class);
			boolean aOnline = usersStatus.isOnline(a);
			boolean bOnline = usersStatus.isOnline(b);
			if (aOnline == bOnline) return a.getName().compareTo(b.getName());
			if (aOnline) return -1;
			return 1;
		}
	};

}
