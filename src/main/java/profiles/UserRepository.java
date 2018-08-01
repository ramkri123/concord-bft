package profiles;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

   List<User> findUsersByConsortiumAndOrganization(Consortium c,
                                                   Organization o);
}
