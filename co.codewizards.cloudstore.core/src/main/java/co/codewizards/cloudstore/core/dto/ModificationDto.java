package co.codewizards.cloudstore.core.dto;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class ModificationDto {

	private long id;
	private long localRevision;

	public long getId() {
		return id;
	}
	public void setId(long id) {
		this.id = id;
	}

	public long getLocalRevision() {
		return localRevision;
	}
	public void setLocalRevision(long localRevision) {
		this.localRevision = localRevision;
	}

	@Override
	public String toString() {
		return this.getClass().getSimpleName() + "[id=" + id
				+ ", localRevision=" + localRevision
				+ "]";
	}
}
