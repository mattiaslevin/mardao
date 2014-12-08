package net.sf.mardao.dao;

import net.sf.mardao.domain.DUser;

/**
 * To test AbstractDao.
 *
 * @author osandstrom Date: 2014-09-03 Time: 20:11
 */
public class DUserDao extends AbstractDao<DUser, Long> {
  public DUserDao(Supplier supplier) {
    super(new DUserMapper(supplier), supplier);
  }

  public Iterable<DUser> queryByDisplayName(String displayName) {
    return queryByField(null, DUserMapper.Field.DISPLAYNAME.getFieldName(), displayName);
  }

  public DUser findByEmail(String email) {
    return queryUniqueByField(null, DUserMapper.Field.EMAIL.getFieldName(), email);
  }
}