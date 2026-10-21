package top.niunaijun.blackbox.fake.frameworks;

import android.os.RemoteException;

import java.util.Collections;
import java.util.List;

import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.user.BUserInfo;
import top.niunaijun.blackbox.core.system.user.IBUserManagerService;

/**
 * Created by Milk on 4/28/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BUserManager extends BlackManager<IBUserManagerService> {
    private static final BUserManager sUserManager = new BUserManager();

    public static BUserManager get() {
        return sUserManager;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.USER_MANAGER;
    }

    public BUserInfo createUser(int userId) {
        try {
            IBUserManagerService service = getService();
            if (service == null) {
                return null;
            }
            return service.createUser(userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void deleteUser(int userId) {
        try {
            IBUserManagerService service = getService();
            if (service == null) {
                return;
            }
            service.deleteUser(userId);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public List<BUserInfo> getUsers() {
        try {
            IBUserManagerService service = getService();
            if (service == null) {
                return Collections.emptyList();
            }
            List<BUserInfo> users = service.getUsers();
            return users == null ? Collections.emptyList() : users;
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Collections.emptyList();
    }
}
